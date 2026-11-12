/*
 * Turnip smoke test for the AYN Thor.
 *
 * Loads a libvulkan_freedreno.so through its Android HAL entry point (HMI -> open ->
 * hwvulkan_device_t), the way the Vulkan loader and AdrenoTools do, then checks device info
 * and runs compute, subgroup, draw and (when VK_EXT_mesh_shader is exposed) task/mesh work.
 *
 *   vktest /path/to/libvulkan_freedreno.so
 */
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define VK_NO_PROTOTYPES
#include <hardware/hwvulkan.h>

#include "shaders.h"

#define CHECK(expr)                                                              \
   do {                                                                          \
      VkResult r_ = (expr);                                                      \
      if (r_ != VK_SUCCESS) {                                                    \
         printf("FAIL %s returned %d (line %d)\n", #expr, r_, __LINE__);         \
         exit(1);                                                                \
      }                                                                          \
   } while (0)

static int failures;
static void
result(bool ok, const char *name, const char *detail)
{
   printf("%s %-28s %s\n", ok ? "PASS" : "FAIL", name, detail);
   if (!ok)
      failures++;
}

static double
now_ms(void)
{
   struct timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static PFN_vkGetInstanceProcAddr gipa;
static PFN_vkGetDeviceProcAddr gdpa;
static VkInstance inst;
static VkPhysicalDevice pdev;
static VkDevice dev;
static VkQueue queue;
static VkCommandPool cmd_pool;
static VkPhysicalDeviceMemoryProperties mem_props;

#define INST_FN(name) static PFN_##name name;
#define DEV_FN(name) static PFN_##name name;
#define INST_FNS(X)                                                              \
   X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties2) \
   X(vkGetPhysicalDeviceFeatures2) X(vkEnumerateDeviceExtensionProperties)       \
   X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceMemoryProperties) \
   X(vkCreateDevice) X(vkGetDeviceProcAddr)
#define DEV_FNS(X)                                                               \
   X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkDeviceWaitIdle) X(vkQueueSubmit)   \
   X(vkQueueWaitIdle) X(vkCreateBuffer) X(vkDestroyBuffer)                       \
   X(vkGetBufferMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory)          \
   X(vkBindBufferMemory) X(vkMapMemory) X(vkCreateImage) X(vkDestroyImage)       \
   X(vkGetImageMemoryRequirements) X(vkBindImageMemory) X(vkCreateImageView)     \
   X(vkDestroyImageView) X(vkCreateShaderModule) X(vkDestroyShaderModule)        \
   X(vkCreateDescriptorSetLayout) X(vkCreatePipelineLayout)                      \
   X(vkCreateComputePipelines) X(vkCreateGraphicsPipelines) X(vkDestroyPipeline) \
   X(vkCreateDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) \
   X(vkCreateCommandPool) X(vkDestroyCommandPool) X(vkAllocateCommandBuffers)    \
   X(vkFreeCommandBuffers) X(vkBeginCommandBuffer) X(vkEndCommandBuffer)         \
   X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) X(vkCmdPushConstants)         \
   X(vkCmdDispatch) X(vkCmdPipelineBarrier) X(vkCmdBeginRendering)               \
   X(vkCmdEndRendering) X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdDraw)      \
   X(vkCmdCopyImageToBuffer)
INST_FNS(INST_FN)
DEV_FNS(DEV_FN)
static PFN_vkCmdDrawMeshTasksEXT vkCmdDrawMeshTasksEXT;

static uint32_t
find_memory(uint32_t type_bits, VkMemoryPropertyFlags want)
{
   for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
      if ((type_bits & (1u << i)) &&
          (mem_props.memoryTypes[i].propertyFlags & want) == want)
         return i;
   }
   printf("FAIL no memory type for bits 0x%x flags 0x%x\n", type_bits, want);
   exit(1);
}

struct buffer {
   VkBuffer buf;
   VkDeviceMemory mem;
   void *map;
   VkDeviceSize size;
};

static struct buffer
make_buffer(VkDeviceSize size, VkBufferUsageFlags usage)
{
   struct buffer b = {.size = size};
   VkBufferCreateInfo info = {
      .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
      .size = size,
      .usage = usage,
   };
   CHECK(vkCreateBuffer(dev, &info, NULL, &b.buf));
   VkMemoryRequirements req;
   vkGetBufferMemoryRequirements(dev, b.buf, &req);
   VkMemoryAllocateInfo alloc = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = req.size,
      .memoryTypeIndex = find_memory(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
   };
   CHECK(vkAllocateMemory(dev, &alloc, NULL, &b.mem));
   CHECK(vkBindBufferMemory(dev, b.buf, b.mem, 0));
   CHECK(vkMapMemory(dev, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map));
   return b;
}

static void
free_buffer(struct buffer *b)
{
   vkDestroyBuffer(dev, b->buf, NULL);
   vkFreeMemory(dev, b->mem, NULL);
}

static VkShaderModule
make_module(const uint32_t *code, size_t size)
{
   VkShaderModuleCreateInfo info = {
      .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
      .codeSize = size,
      .pCode = code,
   };
   VkShaderModule m;
   CHECK(vkCreateShaderModule(dev, &info, NULL, &m));
   return m;
}
#define MODULE(name) make_module(name, sizeof(name))

static VkCommandBuffer
begin_cmd(void)
{
   VkCommandBufferAllocateInfo alloc = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = cmd_pool,
      .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
      .commandBufferCount = 1,
   };
   VkCommandBuffer cmd;
   CHECK(vkAllocateCommandBuffers(dev, &alloc, &cmd));
   VkCommandBufferBeginInfo begin = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
   };
   CHECK(vkBeginCommandBuffer(cmd, &begin));
   return cmd;
}

static void
submit_and_wait(VkCommandBuffer cmd)
{
   CHECK(vkEndCommandBuffer(cmd));
   VkSubmitInfo submit = {
      .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
      .commandBufferCount = 1,
      .pCommandBuffers = &cmd,
   };
   CHECK(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE));
   CHECK(vkQueueWaitIdle(queue));
   vkFreeCommandBuffers(dev, cmd_pool, 1, &cmd);
}

static void
host_read_barrier(VkCommandBuffer cmd, VkPipelineStageFlags src, VkAccessFlags access)
{
   VkMemoryBarrier barrier = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
      .srcAccessMask = access,
      .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
   };
   vkCmdPipelineBarrier(cmd, src, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, NULL, 0, NULL);
}

/* One storage buffer at binding 0, plus an optional 8-byte push constant. */
struct compute_kernel {
   VkDescriptorSetLayout set_layout;
   VkPipelineLayout layout;
   VkDescriptorSet set;
};

static struct compute_kernel
make_kernel(VkBuffer buf)
{
   struct compute_kernel k;
   VkDescriptorSetLayoutBinding binding = {
      .binding = 0,
      .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      .descriptorCount = 1,
      .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
   };
   VkDescriptorSetLayoutCreateInfo set_info = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
      .bindingCount = 1,
      .pBindings = &binding,
   };
   CHECK(vkCreateDescriptorSetLayout(dev, &set_info, NULL, &k.set_layout));
   VkPushConstantRange push = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
   VkPipelineLayoutCreateInfo layout_info = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
      .setLayoutCount = 1,
      .pSetLayouts = &k.set_layout,
      .pushConstantRangeCount = 1,
      .pPushConstantRanges = &push,
   };
   CHECK(vkCreatePipelineLayout(dev, &layout_info, NULL, &k.layout));
   VkDescriptorPoolSize pool_size = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
   VkDescriptorPoolCreateInfo pool_info = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
      .maxSets = 1,
      .poolSizeCount = 1,
      .pPoolSizes = &pool_size,
   };
   VkDescriptorPool pool;
   CHECK(vkCreateDescriptorPool(dev, &pool_info, NULL, &pool));
   VkDescriptorSetAllocateInfo alloc = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
      .descriptorPool = pool,
      .descriptorSetCount = 1,
      .pSetLayouts = &k.set_layout,
   };
   CHECK(vkAllocateDescriptorSets(dev, &alloc, &k.set));
   VkDescriptorBufferInfo buf_info = {buf, 0, VK_WHOLE_SIZE};
   VkWriteDescriptorSet write = {
      .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
      .dstSet = k.set,
      .dstBinding = 0,
      .descriptorCount = 1,
      .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      .pBufferInfo = &buf_info,
   };
   vkUpdateDescriptorSets(dev, 1, &write, 0, NULL);
   return k;
}

static VkPipeline
make_compute_pipeline(const struct compute_kernel *k, VkShaderModule module,
                      uint32_t required_subgroup_size)
{
   VkPipelineShaderStageRequiredSubgroupSizeCreateInfo required = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_REQUIRED_SUBGROUP_SIZE_CREATE_INFO,
      .requiredSubgroupSize = required_subgroup_size,
   };
   VkComputePipelineCreateInfo info = {
      .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
      .stage = {
         .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
         .pNext = required_subgroup_size ? &required : NULL,
         .flags = required_subgroup_size ? VK_PIPELINE_SHADER_STAGE_CREATE_REQUIRE_FULL_SUBGROUPS_BIT : 0,
         .stage = VK_SHADER_STAGE_COMPUTE_BIT,
         .module = module,
         .pName = "main",
      },
      .layout = k->layout,
   };
   VkPipeline pipeline;
   CHECK(vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &info, NULL, &pipeline));
   return pipeline;
}

static void
run_compute(const struct compute_kernel *k, VkPipeline pipeline, uint32_t groups,
            const uint32_t push[2])
{
   VkCommandBuffer cmd = begin_cmd();
   vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
   vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, k->layout, 0, 1, &k->set, 0, NULL);
   if (push)
      vkCmdPushConstants(cmd, k->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, push);
   vkCmdDispatch(cmd, groups, 1, 1);
   host_read_barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
   submit_and_wait(cmd);
}

static void
test_fill(void)
{
   const uint32_t n = 1u << 20;
   struct buffer b = make_buffer(n * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
   struct compute_kernel k = make_kernel(b.buf);
   double t0 = now_ms();
   VkShaderModule module = MODULE(spv_fill_comp);
   VkPipeline pipeline = make_compute_pipeline(&k, module, 0);
   double compile_ms = now_ms() - t0;

   bool ok = true;
   uint32_t bad_at = 0, runs = 20;
   t0 = now_ms();
   for (uint32_t run = 0; run < runs && ok; run++) {
      memset(b.map, 0, n * 4);
      uint32_t push[2] = {3 + run, 7 * run + 1};
      run_compute(&k, pipeline, n / 64, push);
      const uint32_t *v = b.map;
      for (uint32_t i = 0; i < n; i++) {
         if (v[i] != i * push[0] + push[1]) {
            ok = false;
            bad_at = i;
            break;
         }
      }
   }
   char detail[160];
   snprintf(detail, sizeof(detail), "%u runs x %u invocations, compile %.1f ms, %.1f ms/run%s",
            runs, n, compile_ms, (now_ms() - t0) / runs, ok ? "" : " (mismatch)");
   if (!ok)
      snprintf(detail + strlen(detail), sizeof(detail) - strlen(detail), " at %u", bad_at);
   result(ok, "compute: fill 1M uints", detail);
   vkDestroyPipeline(dev, pipeline, NULL);
   vkDestroyShaderModule(dev, module, NULL);
   free_buffer(&b);
}

static void
test_subgroup(uint32_t required, const char *label)
{
   const uint32_t groups = 64, n = groups * 128;
   struct buffer b = make_buffer(n * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
   memset(b.map, 0xff, n * 16);
   struct compute_kernel k = make_kernel(b.buf);
   VkShaderModule module = MODULE(spv_subgroup_comp);
   VkPipeline pipeline = make_compute_pipeline(&k, module, required);
   run_compute(&k, pipeline, groups, NULL);

   const uint32_t (*v)[4] = b.map;
   uint32_t size = v[0][0];
   bool ok = required ? size == required : (size == 32 || size == 64 || size == 128);
   uint32_t bad = 0;
   for (uint32_t i = 0; i < n; i++) {
      if (v[i][0] != size || v[i][1] != size || v[i][2] != 1 || v[i][3] != 7) {
         if (!bad)
            printf("     first bad invocation %u: size=%u total=%u prefix-id=%u flags=%u\n",
                   i, v[i][0], v[i][1], v[i][2], v[i][3]);
         bad++;
      }
   }
   ok = ok && bad == 0;
   char name[64], detail[128];
   snprintf(name, sizeof(name), "subgroups: %s", label);
   snprintf(detail, sizeof(detail), "gl_SubgroupSize=%u; add/scan/shuffle/broadcast/ballot %s",
            size, bad ? "WRONG" : "correct");
   if (bad)
      snprintf(detail + strlen(detail), sizeof(detail) - strlen(detail), " in %u/%u invocations", bad, n);
   result(ok, name, detail);
   vkDestroyPipeline(dev, pipeline, NULL);
   vkDestroyShaderModule(dev, module, NULL);
   free_buffer(&b);
}

/* Renders into a 64x64 RGBA8 target and returns the pixels in a host buffer. */
#define RT 64
struct pixel_check {
   int x, y;
   uint8_t rgba[4];
   const char *what;
};

static void
test_draw(const char *name, VkPipelineShaderStageCreateInfo *stages, uint32_t stage_count,
          bool mesh, const struct pixel_check *checks, int check_count)
{
   VkImageCreateInfo image_info = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .imageType = VK_IMAGE_TYPE_2D,
      .format = VK_FORMAT_R8G8B8A8_UNORM,
      .extent = {RT, RT, 1},
      .mipLevels = 1,
      .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT,
      .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
   };
   VkImage image;
   CHECK(vkCreateImage(dev, &image_info, NULL, &image));
   VkMemoryRequirements req;
   vkGetImageMemoryRequirements(dev, image, &req);
   VkMemoryAllocateInfo alloc = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = req.size,
      .memoryTypeIndex = find_memory(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
   };
   VkDeviceMemory image_mem;
   CHECK(vkAllocateMemory(dev, &alloc, NULL, &image_mem));
   CHECK(vkBindImageMemory(dev, image, image_mem, 0));
   VkImageViewCreateInfo view_info = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
      .image = image,
      .viewType = VK_IMAGE_VIEW_TYPE_2D,
      .format = image_info.format,
      .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
   };
   VkImageView view;
   CHECK(vkCreateImageView(dev, &view_info, NULL, &view));
   struct buffer readback = make_buffer(RT * RT * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);

   VkPipelineLayoutCreateInfo layout_info = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
   VkPipelineLayout layout;
   CHECK(vkCreatePipelineLayout(dev, &layout_info, NULL, &layout));
   VkPipelineVertexInputStateCreateInfo vertex_input = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
   };
   VkPipelineInputAssemblyStateCreateInfo input_assembly = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
      .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
   };
   VkPipelineViewportStateCreateInfo viewport = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
      .viewportCount = 1,
      .scissorCount = 1,
   };
   VkPipelineRasterizationStateCreateInfo raster = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
      .polygonMode = VK_POLYGON_MODE_FILL,
      .cullMode = VK_CULL_MODE_NONE,
      .lineWidth = 1.0f,
   };
   VkPipelineMultisampleStateCreateInfo multisample = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
      .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
   };
   VkPipelineColorBlendAttachmentState blend_attachment = {.colorWriteMask = 0xf};
   VkPipelineColorBlendStateCreateInfo blend = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
      .attachmentCount = 1,
      .pAttachments = &blend_attachment,
   };
   VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
   VkPipelineDynamicStateCreateInfo dynamic = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
      .dynamicStateCount = 2,
      .pDynamicStates = dynamic_states,
   };
   VkPipelineRenderingCreateInfo rendering_info = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO,
      .colorAttachmentCount = 1,
      .pColorAttachmentFormats = &image_info.format,
   };
   VkGraphicsPipelineCreateInfo pipeline_info = {
      .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
      .pNext = &rendering_info,
      .stageCount = stage_count,
      .pStages = stages,
      .pVertexInputState = mesh ? NULL : &vertex_input,
      .pInputAssemblyState = mesh ? NULL : &input_assembly,
      .pViewportState = &viewport,
      .pRasterizationState = &raster,
      .pMultisampleState = &multisample,
      .pColorBlendState = &blend,
      .pDynamicState = &dynamic,
      .layout = layout,
   };
   double t0 = now_ms();
   VkPipeline pipeline;
   CHECK(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &pipeline));
   double compile_ms = now_ms() - t0;

   VkCommandBuffer cmd = begin_cmd();
   VkImageMemoryBarrier to_attachment = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
      .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      .newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .image = image,
      .subresourceRange = view_info.subresourceRange,
   };
   vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, 0, NULL, 0, NULL, 1,
                        &to_attachment);
   VkRenderingAttachmentInfo color = {
      .sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO,
      .imageView = view,
      .imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
      .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
      .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
      .clearValue.color.float32 = {0.0f, 0.0f, 0.0f, 1.0f},
   };
   VkRenderingInfo rendering = {
      .sType = VK_STRUCTURE_TYPE_RENDERING_INFO,
      .renderArea = {{0, 0}, {RT, RT}},
      .layerCount = 1,
      .colorAttachmentCount = 1,
      .pColorAttachments = &color,
   };
   vkCmdBeginRendering(cmd, &rendering);
   vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
   VkViewport vp = {0, 0, RT, RT, 0, 1};
   VkRect2D scissor = {{0, 0}, {RT, RT}};
   vkCmdSetViewport(cmd, 0, 1, &vp);
   vkCmdSetScissor(cmd, 0, 1, &scissor);
   if (mesh)
      vkCmdDrawMeshTasksEXT(cmd, 1, 1, 1);
   else
      vkCmdDraw(cmd, 3, 1, 0, 0);
   vkCmdEndRendering(cmd);
   VkImageMemoryBarrier to_transfer = to_attachment;
   to_transfer.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
   to_transfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
   to_transfer.oldLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
   to_transfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
   vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_transfer);
   VkBufferImageCopy region = {
      .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
      .imageExtent = {RT, RT, 1},
   };
   vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.buf, 1, &region);
   host_read_barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
   submit_and_wait(cmd);

   const uint8_t *px = readback.map;
   bool ok = true;
   char detail[256];
   int len = snprintf(detail, sizeof(detail), "pipeline %.1f ms;", compile_ms);
   for (int i = 0; i < check_count; i++) {
      const uint8_t *p = px + (checks[i].y * RT + checks[i].x) * 4;
      bool match = memcmp(p, checks[i].rgba, 4) == 0;
      ok = ok && match;
      len += snprintf(detail + len, sizeof(detail) - len, " %s(%d,%d)=%02x%02x%02x%s", checks[i].what,
                      checks[i].x, checks[i].y, p[0], p[1], p[2], match ? "" : "!");
   }
   result(ok, name, detail);

   vkDestroyPipeline(dev, pipeline, NULL);
   vkDestroyImageView(dev, view, NULL);
   vkDestroyImage(dev, image, NULL);
   vkFreeMemory(dev, image_mem, NULL);
   free_buffer(&readback);
}

static VkPipelineShaderStageCreateInfo
stage(VkShaderStageFlagBits s, VkShaderModule module)
{
   return (VkPipelineShaderStageCreateInfo){
      .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
      .stage = s,
      .module = module,
      .pName = "main",
   };
}

static bool
has_extension(const VkExtensionProperties *exts, uint32_t count, const char *name)
{
   for (uint32_t i = 0; i < count; i++) {
      if (strcmp(exts[i].extensionName, name) == 0)
         return true;
   }
   return false;
}

int
main(int argc, char **argv)
{
   if (argc != 2) {
      fprintf(stderr, "usage: %s libvulkan_freedreno.so\n", argv[0]);
      return 2;
   }
   setvbuf(stdout, NULL, _IOLBF, 0);

   void *so = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
   if (!so) {
      printf("FAIL dlopen: %s\n", dlerror());
      return 1;
   }
   hwvulkan_module_t *hmi = dlsym(so, HAL_MODULE_INFO_SYM_AS_STR);
   if (!hmi) {
      printf("FAIL no %s symbol\n", HAL_MODULE_INFO_SYM_AS_STR);
      return 1;
   }
   hwvulkan_device_t *hal = NULL;
   if (hmi->common.methods->open(&hmi->common, HWVULKAN_DEVICE_0, (hw_device_t **)&hal) || !hal) {
      printf("FAIL HAL open\n");
      return 1;
   }
   printf("INFO HAL module id=%s name=\"%s\"\n", hmi->common.id, hmi->common.name);

   VkApplicationInfo app = {
      .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "thor-vktest",
      .apiVersion = VK_API_VERSION_1_3,
   };
   VkInstanceCreateInfo inst_info = {
      .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &app,
   };
   double t0 = now_ms();
   CHECK(hal->CreateInstance(&inst_info, NULL, &inst));
   gipa = hal->GetInstanceProcAddr;
#define LOAD_INST(name) name = (PFN_##name)gipa(inst, #name);
   INST_FNS(LOAD_INST)

   uint32_t count = 1;
   CHECK(vkEnumeratePhysicalDevices(inst, &count, &pdev));
   double init_ms = now_ms() - t0;

   VkPhysicalDeviceDriverProperties driver = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
   VkPhysicalDeviceVulkan13Properties p13 = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_PROPERTIES,
      .pNext = &driver,
   };
   VkPhysicalDeviceProperties2 props = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &p13};
   vkGetPhysicalDeviceProperties2(pdev, &props);
   vkGetPhysicalDeviceMemoryProperties(pdev, &mem_props);
   uint32_t api = props.properties.apiVersion;
   printf("INFO device \"%s\", Vulkan %u.%u.%u, driver \"%s\" \"%s\", instance+enumerate %.1f ms\n",
          props.properties.deviceName, VK_API_VERSION_MAJOR(api), VK_API_VERSION_MINOR(api),
          VK_API_VERSION_PATCH(api), driver.driverName, driver.driverInfo, init_ms);
   printf("INFO subgroup sizes %u..%u, required-size stages 0x%x\n", p13.minSubgroupSize,
          p13.maxSubgroupSize, p13.requiredSubgroupSizeStages);

   uint32_t ext_count = 0;
   vkEnumerateDeviceExtensionProperties(pdev, NULL, &ext_count, NULL);
   VkExtensionProperties *exts = calloc(ext_count, sizeof(*exts));
   vkEnumerateDeviceExtensionProperties(pdev, NULL, &ext_count, exts);
   const char *watch[] = {"VK_EXT_mesh_shader", "VK_KHR_maintenance5", "VK_KHR_maintenance6",
                          "VK_KHR_maintenance7", "VK_KHR_line_rasterization",
                          "VK_KHR_pipeline_binary", "VK_KHR_ray_query", "VK_EXT_extended_dynamic_state3"};
   printf("INFO %u device extensions;", ext_count);
   for (unsigned i = 0; i < sizeof(watch) / sizeof(watch[0]); i++)
      printf(" %s%s", has_extension(exts, ext_count, watch[i]) ? "+" : "-", watch[i] + 3);
   printf("\n");
   bool mesh = has_extension(exts, ext_count, "VK_EXT_mesh_shader");

   uint32_t qf_count = 0;
   vkGetPhysicalDeviceQueueFamilyProperties(pdev, &qf_count, NULL);
   VkQueueFamilyProperties qfs[8];
   qf_count = qf_count > 8 ? 8 : qf_count;
   vkGetPhysicalDeviceQueueFamilyProperties(pdev, &qf_count, qfs);
   uint32_t qf = 0;
   while (qf < qf_count && !(qfs[qf].queueFlags & VK_QUEUE_GRAPHICS_BIT))
      qf++;

   VkPhysicalDeviceMeshShaderFeaturesEXT mesh_features = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
      .taskShader = VK_TRUE,
      .meshShader = VK_TRUE,
   };
   VkPhysicalDeviceVulkan13Features f13 = {
      .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES,
      .pNext = mesh ? &mesh_features : NULL,
      .subgroupSizeControl = VK_TRUE,
      .computeFullSubgroups = VK_TRUE,
      .dynamicRendering = VK_TRUE,
   };
   float priority = 1.0f;
   VkDeviceQueueCreateInfo queue_info = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = qf,
      .queueCount = 1,
      .pQueuePriorities = &priority,
   };
   const char *dev_exts[] = {"VK_EXT_mesh_shader"};
   VkDeviceCreateInfo dev_info = {
      .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .pNext = &f13,
      .queueCreateInfoCount = 1,
      .pQueueCreateInfos = &queue_info,
      .enabledExtensionCount = mesh ? 1 : 0,
      .ppEnabledExtensionNames = dev_exts,
   };
   CHECK(vkCreateDevice(pdev, &dev_info, NULL, &dev));
   gdpa = vkGetDeviceProcAddr;
#define LOAD_DEV(name) name = (PFN_##name)gdpa(dev, #name);
   DEV_FNS(LOAD_DEV)
   vkGetDeviceQueue(dev, qf, 0, &queue);
   VkCommandPoolCreateInfo pool_info = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
      .queueFamilyIndex = qf,
   };
   CHECK(vkCreateCommandPool(dev, &pool_info, NULL, &cmd_pool));

   test_fill();
   test_subgroup(0, "default size");
   if (p13.requiredSubgroupSizeStages & VK_SHADER_STAGE_COMPUTE_BIT) {
      char label[32];
      snprintf(label, sizeof(label), "required %u (min)", p13.minSubgroupSize);
      test_subgroup(p13.minSubgroupSize, label);
      snprintf(label, sizeof(label), "required %u (max)", p13.maxSubgroupSize);
      test_subgroup(p13.maxSubgroupSize, label);
   }

   static const uint8_t red[4] = {255, 0, 0, 255}, black[4] = {0, 0, 0, 255},
                        yellow[4] = {255, 255, 0, 255}, green[4] = {0, 255, 0, 255},
                        blue[4] = {0, 0, 255, 255};
   VkShaderModule frag = MODULE(spv_color_frag);
   {
      VkShaderModule vert = MODULE(spv_tri_vert);
      VkPipelineShaderStageCreateInfo stages[] = {stage(VK_SHADER_STAGE_VERTEX_BIT, vert),
                                                  stage(VK_SHADER_STAGE_FRAGMENT_BIT, frag)};
      struct pixel_check checks[] = {{32, 32, {0}, "center"}, {2, 2, {0}, "corner"}};
      memcpy(checks[0].rgba, red, 4);
      memcpy(checks[1].rgba, black, 4);
      test_draw("draw: vertex+fragment", stages, 2, false, checks, 2);
      vkDestroyShaderModule(dev, vert, NULL);
   }
   if (mesh) {
      vkCmdDrawMeshTasksEXT = (PFN_vkCmdDrawMeshTasksEXT)gdpa(dev, "vkCmdDrawMeshTasksEXT");
      VkShaderModule ms = MODULE(spv_meshonly_mesh);
      VkPipelineShaderStageCreateInfo stages[] = {stage(VK_SHADER_STAGE_MESH_BIT_EXT, ms),
                                                  stage(VK_SHADER_STAGE_FRAGMENT_BIT, frag)};
      struct pixel_check checks[] = {{32, 32, {0}, "center"}, {2, 2, {0}, "corner"}};
      memcpy(checks[0].rgba, yellow, 4);
      memcpy(checks[1].rgba, black, 4);
      test_draw("draw: mesh shader", stages, 2, true, checks, 2);

      VkShaderModule ts = MODULE(spv_pair_task), ms2 = MODULE(spv_pair_mesh);
      VkPipelineShaderStageCreateInfo stages2[] = {stage(VK_SHADER_STAGE_TASK_BIT_EXT, ts),
                                                   stage(VK_SHADER_STAGE_MESH_BIT_EXT, ms2),
                                                   stage(VK_SHADER_STAGE_FRAGMENT_BIT, frag)};
      struct pixel_check checks2[] = {{16, 32, {0}, "left"}, {48, 32, {0}, "right"},
                                      {32, 32, {0}, "gap"}, {2, 2, {0}, "corner"}};
      memcpy(checks2[0].rgba, green, 4);
      memcpy(checks2[1].rgba, blue, 4);
      memcpy(checks2[2].rgba, black, 4);
      memcpy(checks2[3].rgba, black, 4);
      test_draw("draw: task+mesh (2 groups)", stages2, 3, true, checks2, 4);
   } else {
      printf("SKIP draw: mesh shader          VK_EXT_mesh_shader not exposed\n");
   }

   CHECK(vkDeviceWaitIdle(dev));
   vkDestroyCommandPool(dev, cmd_pool, NULL);
   vkDestroyDevice(dev, NULL);
   vkDestroyInstance(inst, NULL);
   printf("%s %d failure(s)\n", failures ? "RESULT FAIL" : "RESULT PASS", failures);
   return failures ? 1 : 0;
}
