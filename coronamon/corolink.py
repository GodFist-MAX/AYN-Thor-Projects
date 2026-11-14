"""Coronamon's link between the AYN Thor, Grok Build and GitHub. The coro script loads it.

Bugs: Coronamon keeps a bug log on the Thor (crashes, freezes and native crashes it catches,
problems the Grok-built apps report themselves, and reports from its Report a problem button).
sync takes whatever changed into ~/thor-coronamon/bugs/, opens or updates a GitHub issue for each
bug in a project that is on GitHub (personal details taken out), and tells the Thor which bugs
Grok has, with their issue numbers.

Updates: publish copies a project's files into the GitHub working copy, adds the notes to the
project's README history and the top-level timeline, commits "Update <app> to <version>" with
"Fixes #N" for the bugs it fixes, and pushes. coro install publishes every app update it installs.

Grok: grok-hook runs from Grok Build's SessionStart and PostToolUse hooks. A session in a Thor
folder starts the watcher (which syncs whenever the Thor is plugged in), and new bugs are handed
to the agent once.
"""
import fcntl
import filecmp
import fnmatch
import glob
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time

HOME = os.path.expanduser("~")
DIR = os.path.dirname(os.path.realpath(__file__))
CORO = os.path.join(DIR, "coro")
BUGS = os.path.join(DIR, "bugs")
STATE = os.path.join(DIR, "state")
CONFIG = os.path.join(DIR, "github.json")
INDEX = os.path.join(BUGS, "index.json")
SETTINGS = os.path.join(STATE, "settings.json")
PUBLISHED = os.path.join(STATE, "published.json")
NEWS = os.path.join(STATE, "news.stamp")
TOLD = os.path.join(STATE, "told.stamp")
SESSIONS = os.path.join(STATE, "sessions")
LOCK = os.path.join(STATE, "coro.lock")
WATCH_PID = os.path.join(STATE, "watch.pid")
WATCH_LOG = os.path.join(STATE, "watch.log")
GH = os.path.join(HOME, ".local", "gh", "bin", "gh")
HOOK_FILE = os.path.join(HOME, ".grok", "hooks", "coronamon.json")
HOOK_SCRIPT = os.path.join(DIR, "grok-hook.sh")

C = None  # the coro script: adb, sh, bridge, need, opt, flag, CoroError, ADB, SERIAL


def setup(coro):
    global C
    C = coro


# ---- small helpers ----

class Busy(Exception):
    """Another coro is syncing or publishing."""


class Lock:
    """One coro at a time touches the bug index and the GitHub working copy. Re-entrant."""
    depth = 0
    fd = None

    def __init__(self, wait=60):
        self.wait = wait

    def __enter__(self):
        if Lock.depth == 0:
            os.makedirs(STATE, exist_ok=True)
            fd = os.open(LOCK, os.O_RDWR | os.O_CREAT, 0o600)
            deadline = time.time() + self.wait
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except OSError:
                    if time.time() >= deadline:
                        os.close(fd)
                        raise Busy()
                    time.sleep(0.25)
            Lock.fd = fd
        Lock.depth += 1
        return self

    def __exit__(self, *exc):
        Lock.depth -= 1
        if Lock.depth == 0:
            fcntl.flock(Lock.fd, fcntl.LOCK_UN)
            os.close(Lock.fd)
            Lock.fd = None


def load(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = "%s.%d.tmp" % (path, os.getpid())
    with open(tmp, "w") as f:
        json.dump(data, f, indent=1, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, path)


def now_ms():
    return int(time.time() * 1000)


def day(ms=None):
    return time.strftime("%Y-%m-%d", time.localtime((ms or now_ms()) / 1000))


def when(ms):
    return time.strftime("%b %d %H:%M", time.localtime(ms / 1000)) if ms else "?"


def plural(n, word):
    return "%d %s%s" % (n, word, "" if n == 1 else "s")


def one_line(s, n):
    s = re.sub(r"\s+", " ", s or "").strip()
    return s if len(s) <= n else s[:n - 1] + "…"


def compare_versions(a, b):
    def parts(v):
        return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-+ ]", re.sub(r"[^\d.\-+ ]", "", v or ""))]
    pa, pb = parts(a), parts(b)
    n = max(len(pa), len(pb))
    pa, pb = pa + [0] * (n - len(pa)), pb + [0] * (n - len(pb))
    return (pa > pb) - (pa < pb)


def config():
    cfg = load(CONFIG, None)
    if not cfg:
        raise C.CoroError("missing or unreadable %s" % CONFIG)
    return cfg


def github_on():
    return load(SETTINGS, {}).get("github", True)


def repo_dir(cfg):
    return os.path.expanduser(cfg["repo"])


def project_for_pkg(cfg, pkg):
    for name, p in cfg["projects"].items():
        if pkg and p.get("pkg") == pkg:
            return name
    return None


def project_for_dir(cfg, d):
    if not d:
        return None
    d = os.path.realpath(os.path.expanduser(d))
    for name, p in cfg["projects"].items():
        if os.path.realpath(os.path.expanduser(p["src"])) == d:
            return name
    return None


def thor_state(timeout=8):
    try:
        r = subprocess.run([C.ADB, "-s", C.SERIAL, "get-state"], capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip() if r.returncode == 0 else "gone"
    except (OSError, subprocess.TimeoutExpired):
        return "gone"


def thor_ready():
    return thor_state() == "device"


# ---- keeping personal details out of public issues ----

SECRET_PATTERNS = [
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"), "a GitHub token"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{20,}"), "a GitHub token"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "a private key"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "an AWS key"),
    (re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"), "a Slack token"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b"), "a Google API key"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"), "a login token (JWT)"),
]
EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
URL = re.compile(r"\b(https?|wss?|content|file|intent)://([^/\s'\"<>()]*)([^\s'\"<>()]*)")
STORAGE = re.compile(r"(/(?:sdcard|storage/emulated/\d+|storage/[0-9A-F]{4}-[0-9A-F]{4}|mnt/media_rw/[0-9A-F-]+))((?:/[^/'\":,()\n]+)+)")
MOVIES = re.compile(r"(Movies/)[^/\s]+(?=/)")
PRIVATE_IP = re.compile(r"\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}"
                        r"|100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3})\b")
MAC = re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b")
LONG = re.compile(r"(?<![\w$.])(?=[A-Za-z0-9_\-+/=]*\d)(?=[A-Za-z0-9_\-+/=]*[A-Za-z])[A-Za-z0-9_\-+/=]{32,}")
_secrets = None


def secret_values():
    """Passwords and tokens in the files github.json lists, to make sure none of them is ever posted."""
    global _secrets
    if _secrets is None:
        vals = set()
        for pattern in config().get("secretFiles", []):
            for path in glob.glob(os.path.expanduser(pattern)):
                try:
                    with open(path, errors="replace") as f:
                        vals.update(v.strip() for v in f.read().splitlines() if len(v.strip()) >= 8)
                except OSError:
                    pass
        _secrets = vals
    return _secrets


def _url(m):
    host = m.group(2)
    if host.split(":")[0] in ("127.0.0.1", "localhost"):
        host = "localhost"
    return "%s://%s%s" % (m.group(1), host, "/…" if m.group(3) not in ("", "/") else "")


def _storage(m):
    parts = m.group(2).strip("/").split("/")
    return m.group(1) + "/" + parts[0] + ("/…" if len(parts) > 1 else "")


def redact(text):
    s = text or ""
    for v in secret_values():
        s = s.replace(v, "[redacted]")
    for pat, _ in SECRET_PATTERNS:
        s = pat.sub("[redacted]", s)
    s = URL.sub(_url, s)
    s = EMAIL.sub("[email]", s)
    s = STORAGE.sub(_storage, s)
    s = MOVIES.sub(r"\1[account]", s)
    s = s.replace(HOME, "~")
    s = re.sub(r"/Users/[^/\s]+", "/Users/[user]", s)
    if C is not None:
        s = s.replace(C.SERIAL, "[serial]")
    s = PRIVATE_IP.sub("[ip]", s)
    s = MAC.sub("[mac]", s)
    s = LONG.sub("[redacted]", s)
    return s


# ---- GitHub ----

class GitHubError(Exception):
    pass


OFFLINE = re.compile(r"could not resolve|connection refused|timed? ?out|no such host|network is unreachable|dial tcp"
                     r"|tls|certificate|connection reset|unexpected EOF|offline", re.I)


def gh_api(method, path, data=None, timeout=45):
    if not os.path.exists(GH):
        raise GitHubError("the GitHub CLI isn't at %s" % GH)
    cmd = [GH, "api", "--method", method, path, "-H", "Accept: application/vnd.github+json"]
    if data is not None:
        cmd += ["--input", "-"]
    env = dict(os.environ, GH_NO_UPDATE_NOTIFIER="1", GH_PROMPT_DISABLED="1", NO_COLOR="1")
    try:
        r = subprocess.run(cmd, input=json.dumps(data) if data is not None else None, capture_output=True, text=True,
                           timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        raise GitHubError("GitHub didn't answer (offline?)")
    if r.returncode != 0:
        msg = re.sub(r"gh[opusr]_\w+", "gh*_…", (r.stderr.strip() or r.stdout.strip())[:300])
        raise GitHubError(msg or "gh api failed")
    return json.loads(r.stdout) if r.stdout.strip() else {}


LABELS = {
    "from-thor": ("ff8a1e", "Found on the AYN Thor by Coronamon"),
    "crash": ("d73a4a", "The app crashed"),
    "freeze": ("fbca04", "The app stopped responding"),
    "script-error": ("c5def5", "A script error in one of the app's web pages"),
    "launch": ("bfd4f2", "Something wouldn't open or start"),
    "user-report": ("0e8a16", "Reported with Coronamon's Report a problem button"),
}
KIND_LABEL = {"crash": "crash", "native": "crash", "renderer": "crash", "anr": "freeze", "freeze": "freeze",
              "js": "script-error", "launch": "launch", "report": "user-report"}
KIND_TEXT = {"crash": "Crash (Java exception)", "native": "Native crash", "anr": "Froze (not responding)",
             "renderer": "Web page renderer crashed", "js": "Script error", "launch": "Couldn't start something",
             "freeze": "Froze", "error": "A problem the app noticed", "report": "Reported from the Thor"}
KIND_SHORT = {"crash": "crash", "native": "native crash", "anr": "froze", "renderer": "page renderer crash",
              "js": "script error", "launch": "launch failure", "freeze": "froze", "error": "problem", "report": "user report"}


def issue_project(b, cfg):
    """The GitHub project a bug belongs to, or None if it stays between the Thor and Grok."""
    if b.get("kind") == "report" and not b.get("github"):
        return None
    name = project_for_pkg(cfg, b.get("pkg"))
    if name:
        rule = cfg["projects"][name].get("issues", "all")
        return name if rule == "all" or (isinstance(rule, list) and b.get("kind") in rule) else None
    driver = (b.get("context") or {}).get("driver") or ""
    if b.get("kind") == "native" and re.search(r"thor", driver, re.I):
        for n, p in cfg["projects"].items():
            if p.get("issues") == "driver":
                return n
    return None


def issue_title(b, cfg, proj):
    t = redact(b.get("title") or "Problem")
    if b.get("kind") == "report":
        t = "Report: " + t
    return one_line("[%s] %s" % (cfg["projects"][proj]["label"], t), 120)


def public_excerpt(b):
    """The stack trace for crashes and freezes, without the header. App-reported details stay private."""
    if b.get("kind") not in ("crash", "anr", "native"):
        return ""
    lines = [l for l in (b.get("text") or "").splitlines() if not re.match(r"(Process|Package|Foreground|Cmdline):", l)]
    return redact("\n".join(lines[:30]).strip()).replace("```", "'''")


def issue_body(b, cfg, proj):
    p = cfg["projects"][proj]
    versions = b.get("versions") or []
    count = b.get("count") or 1
    rows = ["<!-- coronamon:bug=%s -->" % b["id"],
            "- **App:** %s (`%s`)" % (redact(b.get("app") or p["label"]), b.get("pkg") or "?"),
            "- **What happened:** %s" % KIND_TEXT.get(b.get("kind"), b.get("kind")),
            "- **Version:** %s" % (", ".join(versions) or "unknown"),
            "- **Seen:** %s, first %s, last %s" % (plural(count, "time"), day(b.get("first")), day(b.get("last")))]
    driver = (b.get("context") or {}).get("driver")
    if driver:
        rows.append("- **Vulkan driver:** %s" % redact(driver))
    if b.get("state") == "fixed" and b.get("fixedIn"):
        rows.append("- **Fixed in:** %s" % b["fixedIn"])
    out = "\n".join(rows)
    if b.get("kind") == "report":
        if b.get("category"):
            out += "\n\n**Kind of problem:** %s" % redact(b["category"])
        note = redact(b.get("note") or "").strip()
        if note:
            out += "\n\n" + "\n".join("> " + l for l in note.splitlines()[:40])
    else:
        ex = public_excerpt(b)
        if ex:
            out += "\n\n```\n%s\n```" % ex
    return out + "\n\n<sub>Filed by Coronamon from the AYN Thor. Logs and screenshots stay with Grok Build on the Mac.</sub>"


def ensure_labels(cfg, cache):
    if cache.get("labels"):
        return
    have = {l["name"] for l in gh_api("GET", "repos/%s/labels?per_page=100" % cfg["slug"])}
    want = dict(LABELS)
    for name, p in cfg["projects"].items():
        if p.get("issues", "all") != "none":
            want[name] = ("1d76db", p["label"])
    for name, (color, desc) in want.items():
        if name not in have:
            gh_api("POST", "repos/%s/labels" % cfg["slug"], {"name": name, "color": color, "description": desc[:100]})
    cache["labels"] = True


def existing_issues(cfg, cache):
    """Issues Coronamon filed before, by bug id, in case the local index was lost."""
    if "byBug" not in cache:
        found = {}
        for page in range(1, 6):
            items = gh_api("GET", "repos/%s/issues?state=all&labels=from-thor&per_page=100&page=%d" % (cfg["slug"], page))
            for it in items:
                m = re.search(r"coronamon:bug=(\w+)", it.get("body") or "")
                if m:
                    found[m.group(1)] = {"number": it["number"], "url": it["html_url"], "state": it["state"]}
            if len(items) < 100:
                break
        cache["byBug"] = found
    return cache["byBug"]


def file_issues(idx, cfg, out, dry=False):
    cache = {}
    for b in sorted(idx["bugs"].values(), key=lambda b: b.get("first") or 0):
        proj = issue_project(b, cfg)
        if not proj:
            continue
        L = b.setdefault("local", {})
        if L.get("issue") and L.get("publishedRev") == b.get("rev") and not state_change(b):
            continue
        if not L.get("issue") and b.get("state") == "ignored":
            continue
        if dry:
            out["preview"].append((b, proj))
            continue
        try:
            if not L.get("issue"):
                hit = existing_issues(cfg, cache).get(b["id"])
                if hit:
                    L.update(issue=hit["number"], url=hit["url"], issueState=hit["state"])
                else:
                    ensure_labels(cfg, cache)
                    labels = ["bug", "from-thor", proj] + ([KIND_LABEL[b["kind"]]] if b.get("kind") in KIND_LABEL else [])
                    r = gh_api("POST", "repos/%s/issues" % cfg["slug"],
                               {"title": issue_title(b, cfg, proj), "body": issue_body(b, cfg, proj), "labels": labels})
                    L.update(issue=r["number"], url=r["html_url"], issueState="open", publishedRev=b.get("rev"),
                             versionsSeen=list(b.get("versions") or []), reopenedSeen=b.get("reopened") or 0)
                    out["opened"].append("#%d %s" % (r["number"], issue_title(b, cfg, proj)))
            update_issue(b, cfg, proj, out)
        except GitHubError as e:
            out["errors"].append("GitHub: %s" % e)
            return


def state_change(b):
    L = b.get("local") or {}
    st, gh = b.get("state"), L.get("issueState")
    return (st == "open" and gh == "closed") or (st in ("fixed", "ignored") and gh != "closed")


def update_issue(b, cfg, proj, out):
    L = b["local"]
    n = L["issue"]
    app = b.get("app") or cfg["projects"][proj]["label"]
    comments, patch = [], {}
    if L.get("publishedRev") != b.get("rev"):
        patch["body"] = issue_body(b, cfg, proj)
        new_versions = [v for v in b.get("versions") or [] if v not in (L.get("versionsSeen") or [])]
        if (b.get("reopened") or 0) > (L.get("reopenedSeen") or 0):
            comments.append("This came back in %s %s." % (app, b.get("backIn") or "a later version"))
        elif new_versions and b.get("state") == "open" and L.get("versionsSeen"):
            comments.append("Also seen in %s %s." % (app, ", ".join(new_versions)))
    st = b.get("state")
    if st == "open" and L.get("issueState") == "closed":
        patch["state"] = "open"
        if not comments:
            comments.append("Opened again from the Thor.")
    elif st == "fixed" and L.get("issueState") != "closed":
        patch.update(state="closed", state_reason="completed")
        comments.append("Fixed in %s %s." % (app, b["fixedIn"]) if b.get("fixedIn") else "Marked fixed on the Thor.")
    elif st == "ignored" and L.get("issueState") != "closed":
        patch.update(state="closed", state_reason="not_planned")
        comments.append("Closed from the Thor as not a bug.")
    for c in comments:
        gh_api("POST", "repos/%s/issues/%d/comments" % (cfg["slug"], n), {"body": c})
    if patch:
        gh_api("PATCH", "repos/%s/issues/%d" % (cfg["slug"], n), patch)
    if "state" in patch:
        L["issueState"] = patch["state"]
        out["updated"].append("#%d %s" % (n, "closed" if patch["state"] == "closed" else "reopened"))
    elif comments:
        out["updated"].append("#%d updated" % n)
    L.update(publishedRev=b.get("rev"), versionsSeen=list(b.get("versions") or []), reopenedSeen=b.get("reopened") or 0)


# ---- sync ----

def new_result():
    return {"connected": False, "new": [], "changed": [], "opened": [], "updated": [], "preview": [], "errors": [],
            "published": [], "pushed": None}


def pull_files(b):
    """Reports, logs and screenshots for a bug, into ~/thor-coronamon/bugs/<id>/."""
    d = os.path.join(BUGS, b["id"])
    got = []
    for key in ("reports", "logs", "shots"):
        for path in b.get(key) or []:
            dest = os.path.join(d, os.path.basename(path))
            if not os.path.exists(dest):
                os.makedirs(d, exist_ok=True)
                C.adb("pull", path, dest, check=False, timeout=120)
            if os.path.exists(dest):
                got.append(os.path.basename(path))
    return got


def pull_bugs(idx, out, dry):
    seen = set()
    for _ in range(20):
        res = C.need(C.bridge("bugs", "unsynced", max="40"))
        batch = [b for b in res.get("bugs") or [] if b["id"] not in seen]
        if not batch:
            break
        for b in batch:
            seen.add(b["id"])
            old = idx["bugs"].get(b["id"])
            local = dict((old or {}).get("local") or {})
            local.pop("pendingState", None)
            if not dry:
                local["files"] = sorted(set(local.get("files") or []) | set(pull_files(b)))
                local["pulledAt"] = now_ms()
            entry = dict(b)
            entry["local"] = local
            idx["bugs"][b["id"]] = entry
            out["new" if old is None else "changed"].append(b["id"])
        if dry:
            break
        C.need(C.bridge("bugs-ack", acks=[{"id": b["id"], "rev": b["rev"]} for b in batch]))


def send_pending_states(idx):
    """Bug states changed on the Mac while the Thor wasn't plugged in."""
    for b in idx["bugs"].values():
        p = (b.get("local") or {}).get("pendingState")
        if p:
            res = C.bridge("bug-state", b["id"], state=p["state"], version=p.get("version") or "", react="quiet")
            if res.get("ok") or "no bug" in str(res.get("error")):
                b["local"].pop("pendingState", None)


def send_issue_acks(idx):
    acks = []
    for b in idx["bugs"].values():
        L = b.get("local") or {}
        if L.get("issue") and L.get("thorIssue") != L.get("issue"):
            acks.append({"id": b["id"], "rev": b.get("rev") or 0, "issue": L["issue"], "url": L.get("url") or ""})
    if acks:
        C.need(C.bridge("bugs-ack", acks=acks))
        for a in acks:
            idx["bugs"][a["id"]]["local"]["thorIssue"] = a["issue"]


def sync(github=True, dry=False, wait=60, publish_installed=True):
    out = new_result()
    cfg = config()
    with Lock(wait):
        idx = load(INDEX, {"bugs": {}})
        idx.setdefault("bugs", {})
        if thor_ready():
            out["connected"] = True
            if not dry:
                send_pending_states(idx)
            pull_bugs(idx, out, dry)
        if github and github_on():
            file_issues(idx, cfg, out, dry)
        if dry:
            return out
        if out["connected"]:
            send_issue_acks(idx)
            idx["lastSync"] = now_ms()
        save(INDEX, idx)
        if untold(idx):
            touch_news()
        if out["connected"] and github and github_on() and publish_installed:
            auto_publish(cfg, out)
        if github and github_on():
            out["pushed"] = push_pending(cfg)
    return out


def describe(out):
    bits = []
    if not out["connected"]:
        bits.append("The Thor isn't plugged in.")
    else:
        bits.append("Thor: %s, %d changed." % (plural(len(out["new"]), "new bug"), len(out["changed"])))
    if out["opened"]:
        bits.append("Opened " + ", ".join(out["opened"]) + ".")
    if out["updated"]:
        bits.append("Updated " + ", ".join(out["updated"]) + ".")
    for p in out["published"]:
        bits.append(p)
    if out["pushed"] and out["pushed"] not in ("nothing to push",):
        bits.append("Git: %s." % out["pushed"])
    bits += out["errors"]
    return " ".join(bits)


def touch_news():
    os.makedirs(STATE, exist_ok=True)
    t = time.time()
    try:
        t = max(t, os.path.getmtime(TOLD) + 1.5)
    except OSError:
        pass
    open(NEWS, "a").close()
    os.utime(NEWS, (t, t))


def mark_told():
    os.makedirs(STATE, exist_ok=True)
    open(TOLD, "a").close()
    try:
        t = os.path.getmtime(NEWS)
    except OSError:
        t = time.time()
    os.utime(TOLD, (t, t))


def tell_key(b):
    return "%s|%s|%s" % (b.get("state"), (b.get("versions") or [""])[-1], b.get("reopened") or 0)


def untold(idx):
    news = [b for b in idx.get("bugs", {}).values()
            if b.get("state") == "open" and (b.get("local") or {}).get("told") != tell_key(b)]
    return sorted(news, key=lambda b: b.get("last") or 0, reverse=True)


def digest(news, limit=12):
    lines = ["Coronamon (the AYN Thor companion) found %s since Grok last heard. Bug log: ~/thor-coronamon/bugs/" %
             plural(len(news), "new problem")]
    for b in news[:limit]:
        L = b.get("local") or {}
        v = (b.get("versions") or [""])[-1]
        bits = [KIND_SHORT.get(b.get("kind"), b.get("kind")),
                "seen %d times" % b["count"] if (b.get("count") or 1) > 1 else None,
                "last " + when(b.get("last")),
                "GitHub #%d" % L["issue"] if L.get("issue") else None,
                "came back after a fix" if b.get("reopened") else None,
                "not a Grok Build app" if b.get("external") else None]
        lines.append("- %s %s%s: %s (%s)" % (b["id"], b.get("app") or b.get("pkg") or "the Thor", " " + v if v else "",
                                             one_line(b.get("title"), 160), ", ".join(x for x in bits if x)))
        if b.get("kind") == "report" and b.get("note"):
            lines.append("  The user wrote: " + one_line(b["note"], 300))
    if len(news) > limit:
        lines.append("- and %d more (coro bugs)" % (len(news) - limit))
    lines += ["", "Tell the user about these and offer to fix them. `~/thor-coronamon/coro bug <id>` shows the details, "
                  "the report and the log paths. After a fix, install it with `coro install <apk> --fixes <id> --notes "
                  "\"<what changed>\"`: that marks the bug fixed on the Thor, closes its GitHub issue and publishes the update."]
    return "\n".join(lines)


def user_summary(news):
    what = ", ".join("%s %s" % (b.get("app") or "Thor", KIND_SHORT.get(b.get("kind"), "problem")) for b in news[:3])
    more = " and %d more" % (len(news) - 3) if len(news) > 3 else ""
    return "Coronamon: %s from the Thor (%s%s). Grok will pick them up." % (plural(len(news), "new problem"), what, more)


# ---- publishing updates to GitHub ----

DEFAULT_EXCLUDE = ["keys/", "build/", "__pycache__/", "*.pyc", "*.jks", "*.keystore", "*.pass", "*.idsig", "*.apk",
                   "*.log", "home.json"]


def excluded(rel, patterns):
    parts = rel.split("/")
    if any(p.startswith(".") for p in parts):
        return True
    for pat in patterns:
        if pat.endswith("/"):
            if pat[:-1] in parts[:-1]:
                return True
        elif fnmatch.fnmatch(parts[-1], pat):
            return True
    return False


def mapping(cfg, name):
    """[(source file, path in the repo)] for a project, and the repo folders that mirror source folders."""
    p = cfg["projects"][name]
    src = os.path.expanduser(p["src"])
    dest = p.get("dir", name)
    pats = cfg.get("exclude", DEFAULT_EXCLUDE)
    pairs, trees = [], []
    for entry in p["files"]:
        frm, to = (entry["from"], entry["to"]) if isinstance(entry, dict) else (entry, entry)
        s = os.path.join(src, frm)
        if frm.endswith("/"):
            trees.append(os.path.join(dest, to.rstrip("/")))
            for root, dirs, files in os.walk(s):
                dirs[:] = sorted(d for d in dirs if not excluded(os.path.relpath(os.path.join(root, d, "x"), src), pats))
                for f in sorted(files):
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, s)
                    if not excluded(os.path.join(frm, rel), pats):
                        pairs.append((full, os.path.join(dest, to, rel)))
        elif os.path.isfile(s):
            pairs.append((s, os.path.join(dest, to)))
    return pairs, trees


def scan_secrets(paths):
    vals = [v.encode() for v in secret_values()]
    problems = []
    for path in paths:
        try:
            with open(path, "rb") as f:
                data = f.read(8 * 1024 * 1024)
        except OSError:
            continue
        if any(v in data for v in vals):
            problems.append((path, "a password or token from a keys file"))
            continue
        if b"\0" in data[:8192]:
            continue
        text = data.decode("utf-8", "replace")
        for pat, what in SECRET_PATTERNS:
            if pat.search(text):
                problems.append((path, what))
                break
    return problems


def git(R, *args, check=True, timeout=120):
    r = subprocess.run(["git", "-C", R] + list(args), capture_output=True, text=True, timeout=timeout,
                       env=dict(os.environ, GIT_TERMINAL_PROMPT="0"))
    if check and r.returncode != 0:
        raise C.CoroError("git %s: %s" % (args[0], (r.stderr or r.stdout).strip()[:300]))
    return r


def unpushed(R):
    r = git(R, "rev-list", "--count", "@{u}..HEAD", check=False)
    return int(r.stdout.strip() or 0) if r.returncode == 0 else 0


def push_repo(R):
    if not unpushed(R):
        return "nothing to push"
    for attempt in range(2):
        r = git(R, "push", "--quiet", "origin", "HEAD", check=False, timeout=180)
        if r.returncode == 0:
            return "pushed"
        err = (r.stderr or r.stdout).strip()
        if OFFLINE.search(err):
            return "waiting to push (offline)"
        if attempt == 0 and re.search(r"rejected|fetch first|non-fast-forward", err):
            if git(R, "pull", "--rebase", "--quiet", check=False, timeout=180).returncode != 0:
                git(R, "rebase", "--abort", check=False)
                break
            continue
        break
    raise C.CoroError("git push failed: %s" % re.sub(r"gh[opusr]_\w+", "gh*_…", err[:300]))


def push_pending(cfg):
    """Pushes commits made while offline, then tells the Thor about updates that just went up."""
    R = repo_dir(cfg)
    try:
        status = push_repo(R)
    except C.CoroError as e:
        return str(e)
    if status in ("pushed", "nothing to push"):
        pub = load(PUBLISHED, {})
        changed = False
        for name, rec in pub.items():
            if rec.get("pushed") is False:
                rec["pushed"] = True
                changed = True
                tell_thor_published(cfg, name, rec)
        if changed:
            save(PUBLISHED, pub)
    return status


def tell_thor_published(cfg, name, rec):
    p = cfg["projects"][name]
    if not thor_ready():
        return
    try:
        C.bridge("published", p.get("pkg") or "", retries=3, label=p["label"], version=rec.get("version") or "",
                 url="https://github.com/%s/commit/%s" % (cfg["slug"], rec.get("commit", "")))
    except Exception:
        pass


def add_history(path, note):
    """A '- **date:** note' line at the end of the README's History section."""
    with open(path) as f:
        lines = f.read().split("\n")
    entry = "- **%s:** %s" % (day(), note.strip())
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == "## History")
    except StopIteration:
        while lines and not lines[-1].strip():
            lines.pop()
        lines += ["", "## History", "", entry, ""]
    else:
        end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
        last = max((i for i in range(start + 1, end) if lines[i].strip()), default=start)
        lines.insert(last + 1, entry)
        if last == start:
            lines.insert(last + 1, "")
    with open(path, "w") as f:
        f.write("\n".join(lines))


def add_timeline(path, label, summary):
    """Adds 'label summary' to today's line in the top README's Timeline, or starts today's line."""
    with open(path) as f:
        lines = f.read().split("\n")
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == "## Timeline")
    except StopIteration:
        return
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    text = summary.strip().rstrip(".")
    prefix = "- **%s:**" % day()
    for i in range(start + 1, end):
        if lines[i].startswith(prefix):
            lines[i] = lines[i].rstrip().rstrip(".") + "; " + text
            break
    else:
        last = max((i for i in range(start + 1, end) if lines[i].startswith("- ")), default=start + 1)
        lines.insert(last + 1, "%s %s" % (prefix, text))
    with open(path, "w") as f:
        f.write("\n".join(lines))


def summarize(label, notes):
    first = re.split(r"(?<=[.!?])\s", notes.strip(), 1)[0].rstrip(".!")
    if re.match(r"[A-Z][a-z]", first):
        first = first[0].lower() + first[1:]
    return one_line("%s: %s" % (label, first), 140)


def name_list(paths, base, n=10):
    rel = [os.path.relpath(p, base) for p in paths]
    return ", ".join(rel[:n]) + (" and %d more" % (len(rel) - n) if len(rel) > n else "")


def publish(name, version=None, notes=None, summary=None, issues=(), push=True, dry=False):
    """Copies the project into the GitHub working copy, commits the update and pushes it. Returns a summary line."""
    cfg = config()
    if name not in cfg["projects"]:
        raise C.CoroError("no project %s in %s (have %s)" % (name, CONFIG, ", ".join(cfg["projects"])))
    p = cfg["projects"][name]
    R = repo_dir(cfg)
    dest = p.get("dir", name)
    with Lock(120):
        if git(R, "diff", "--cached", "--quiet", check=False).returncode != 0:
            raise C.CoroError("%s has staged changes that aren't Coronamon's; commit or unstage them first" % R)
        pairs, trees = mapping(cfg, name)
        changed, added = [], []
        for s, rel in pairs:
            d = os.path.join(R, rel)
            if not os.path.exists(d):
                added.append((s, rel))
            elif not filecmp.cmp(s, d, shallow=False):
                changed.append((s, rel))
        wanted = {rel for _, rel in pairs}
        removed = []
        for t in trees:
            tracked = git(R, "ls-files", "--", t).stdout.split("\n")
            removed += [f for f in tracked if f and f not in wanted]
        if not (changed or added or removed):
            if not dry:
                record_published(cfg, name, version, None, True)
            return "%s%s: no file changes to publish." % (p["label"], " " + version if version else "")
        problems = scan_secrets([s for s, _ in changed + added])
        if problems:
            raise C.CoroError("not publishing %s: %s" % (p["label"], "; ".join(
                "%s has %s" % (s.replace(HOME, "~"), what) for s, what in problems[:5])))
        subject = "Update %s%s" % (p["label"], " to " + version if version else "")
        body = []
        if notes:
            body.append(notes.strip())
        base = os.path.join(R, dest)
        files = []
        if changed:
            files.append("Changed: " + name_list([os.path.join(R, r) for _, r in changed], base))
        if added:
            files.append("Added: " + name_list([os.path.join(R, r) for _, r in added], base))
        if removed:
            files.append("Removed: " + name_list([os.path.join(R, r) for r in removed], base))
        body.append("\n".join(files))
        if issues:
            body.append("\n".join("Fixes #%d" % n for n in sorted(set(issues))))
        if dry:
            return "Would commit to %s:\n%s\n\n%s" % (R, subject, "\n\n".join(body))
        for s, rel in changed + added:
            d = os.path.join(R, rel)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            shutil.copy2(s, d)
        paths = [rel for _, rel in changed + added]
        if removed:
            git(R, "rm", "--quiet", "--", *removed)
        if notes:
            readme = os.path.join(R, dest, "README.md")
            if os.path.exists(readme):
                add_history(readme, notes)
                paths.append(os.path.join(dest, "README.md"))
            top = os.path.join(R, "README.md")
            if os.path.exists(top):
                add_timeline(top, p["label"], summary or summarize(p["label"], notes))
                paths.append("README.md")
        for i in range(0, len(paths), 100):
            git(R, "add", "--", *paths[i:i + 100])
        git(R, "commit", "--quiet", "-m", subject, "-m", "\n\n".join(body))
        sha = git(R, "rev-parse", "HEAD").stdout.strip()
        status = push_repo(R) if push else "committed (not pushed)"
        pushed = status == "pushed"
        rec = record_published(cfg, name, version, sha, pushed)
        if pushed:
            tell_thor_published(cfg, name, rec)
        n = len(changed) + len(added) + len(removed)
        return "GitHub: %s (%s, %s): %s." % (subject, sha[:7], plural(n, "file"), status)


def record_published(cfg, name, version, sha, pushed):
    pub = load(PUBLISHED, {})
    rec = pub.get(name) or {}
    if version:
        rec["version"] = version
    if sha:
        rec.update(commit=sha, at=now_ms(), pushed=pushed)
    pub[name] = rec
    save(PUBLISHED, pub)
    return rec


def newest_source(cfg, name):
    pairs, _ = mapping(cfg, name)
    return max((os.path.getmtime(s) for s, _ in pairs), default=0)


def auto_publish(cfg, out):
    """Publishes app updates installed without coro install (or with --no-publish since), once their source settles."""
    pub = load(PUBLISHED, {})
    try:
        apps = C.need(C.bridge("registry", retries=3))["apps"]
    except Exception:
        return
    for a in apps:
        name = project_for_pkg(cfg, a.get("pkg"))
        v = a.get("versionName")
        last = (pub.get(name) or {}).get("version") if name else None
        if not name or not v or not last or compare_versions(v, last) <= 0:
            continue
        installed = max(a.get("lastInstall") or 0, a.get("lastUpdate") or 0)
        if newest_source(cfg, name) * 1000 > installed + 120_000:
            continue
        try:
            out["published"].append(publish(name, version=v))
        except C.CoroError as e:
            out["errors"].append(str(e))


def after_install(info, project_dir, notes=None, summary=None, fixes=None, publish_it=True):
    """coro install: marks fixed bugs, publishes the update to GitHub, then syncs so the issues close."""
    cfg = config()
    ids = [x for x in re.split(r"[,\s]+", fixes or "") if x]
    issues = []
    if ids:
        with Lock(60):
            idx = load(INDEX, {"bugs": {}})
            for bid in ids:
                res = C.bridge("bug-state", bid, state="fixed", version=info["version"])
                print("Marked %s fixed in %s." % (bid, info["version"]) if res.get("ok") else "coro: %s" % res.get("error"))
                b = idx.get("bugs", {}).get(bid)
                if b:
                    b.update(state="fixed", fixedIn=info["version"])
                    if (b.get("local") or {}).get("issue"):
                        issues.append(b["local"]["issue"])
            save(INDEX, idx)
    name = project_for_pkg(cfg, info["pkg"]) or project_for_dir(cfg, project_dir)
    if name and publish_it and github_on():
        try:
            print(publish(name, version=info["version"], notes=notes, summary=summary, issues=issues))
        except C.CoroError as e:
            print("coro: GitHub not updated: %s" % e)
    elif name and not publish_it:
        record_published(cfg, name, info["version"], None, True)
        print("Not published to GitHub (--no-publish); the next update that is published will include these changes.")
    if ids:
        try:
            out = sync(publish_installed=False)
            if out["updated"] or out["errors"]:
                print(describe(out))
        except Busy:
            pass


# ---- the watcher ----

def watcher_pid():
    try:
        pid = int(open(WATCH_PID).read().strip())
        os.kill(pid, 0)
    except (OSError, ValueError):
        return None
    r = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True)
    return pid if "coro" in r.stdout and "watch" in r.stdout else None


def ensure_watcher():
    if watcher_pid():
        return False
    subprocess.Popen([sys.executable, CORO, "watch", "--daemon"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True).wait(timeout=15)
    return True


def wlog(msg):
    os.makedirs(STATE, exist_ok=True)
    try:
        if os.path.getsize(WATCH_LOG) > 256 * 1024:
            os.replace(WATCH_LOG, WATCH_LOG + ".1")
    except OSError:
        pass
    with open(WATCH_LOG, "a") as f:
        f.write("%s  %s\n" % (time.strftime("%m-%d %H:%M:%S"), msg))


def notify_mac(text):
    safe = text.replace("\\", "").replace('"', "'")
    subprocess.run(["osascript", "-e", 'display notification "%s" with title "Coronamon"' % safe],
                   capture_output=True, timeout=10)


def daemonize():
    """Detaches from the terminal or hook that started it. Returns True in the detached process."""
    pid = os.fork()
    if pid > 0:
        os.waitpid(pid, 0)
        return False
    os.setsid()
    if os.fork() > 0:
        os._exit(0)
    os.chdir("/")
    null = os.open(os.devnull, os.O_RDWR)
    for fd in (0, 1, 2):
        os.dup2(null, fd)
    os.closerange(3, 256)
    return True


def watch_loop():
    os.makedirs(STATE, exist_ok=True)
    only = os.open(WATCH_PID + ".lock", os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(only, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        return
    with open(WATCH_PID, "w") as f:
        f.write(str(os.getpid()))
    signal.signal(signal.SIGTERM, lambda *a: (wlog("stopped"), os._exit(0)))
    code = (os.path.getmtime(__file__), os.path.getmtime(CORO))
    wlog("watching for the Thor (pid %d)" % os.getpid())
    connected, last_rev, last_check, last_sync = False, None, 0.0, 0.0
    while True:
        try:
            if (os.path.getmtime(__file__), os.path.getmtime(CORO)) != code:
                wlog("coro changed; restarting")
                os.execv(sys.executable, [sys.executable, CORO, "watch", "--run"])
            if thor_state(timeout=10) == "device":
                reason = None
                if not connected:
                    connected = True
                    wlog("Thor plugged in")
                    time.sleep(3)
                    reason = "plugged in"
                elif time.time() - last_check > 30:
                    last_check = time.time()
                    rev = C.sh("cat /sdcard/Coronamon/bugs.rev 2>/dev/null", timeout=20).strip()
                    if rev and rev != last_rev:
                        reason = "new bugs" if last_rev is not None else None
                        last_rev = rev
                    if reason is None and time.time() - last_sync > 3600:
                        reason = "hourly"
                if reason:
                    last_sync = time.time()
                    watch_sync(reason)
                    last_rev = C.sh("cat /sdcard/Coronamon/bugs.rev 2>/dev/null", timeout=20).strip() or last_rev
            elif connected:
                connected = False
                wlog("Thor unplugged")
        except Exception as e:
            wlog("error: %s" % e)
        time.sleep(5)


def watch_sync(reason):
    try:
        out = sync(wait=120)
    except Busy:
        wlog("sync (%s) skipped: another coro is busy" % reason)
        return
    except Exception as e:
        wlog("sync (%s) failed: %s" % (reason, e))
        return
    wlog("sync (%s): %s" % (reason, describe(out)))
    if out["new"] or out["opened"] or out["published"]:
        bits = []
        if out["new"]:
            bits.append("%s from the Thor" % plural(len(out["new"]), "new bug"))
        if out["opened"]:
            bits.append("%s on GitHub" % plural(len(out["opened"]), "new issue"))
        if out["published"]:
            bits.append("%s published" % plural(len(out["published"]), "update"))
        notify_mac(", ".join(bits) + ".")


# ---- Grok Build hooks ----

def thor_workspace(root):
    root = os.path.realpath(os.path.expanduser(root or ""))
    if root == os.path.realpath(HOME):
        return True
    dirs = [repo_dir(config())] + [os.path.expanduser(p["src"]) for p in config()["projects"].values()]
    dirs += glob.glob(os.path.join(HOME, "thor-*"))
    return any(root == os.path.realpath(d) or root.startswith(os.path.realpath(d) + "/") for d in dirs)


def hook_input():
    try:
        data = json.loads(sys.stdin.read() or "{}") if not sys.stdin.isatty() else {}
    except ValueError:
        data = {}
    sid = os.environ.get("GROK_SESSION_ID") or data.get("sessionId") or data.get("session_id") or ""
    root = os.environ.get("GROK_WORKSPACE_ROOT") or data.get("workspaceRoot") or data.get("cwd") or os.getcwd()
    return re.sub(r"[^A-Za-z0-9_.-]", "_", sid), root


def hook_session_start():
    sid, root = hook_input()
    if not sid or not thor_workspace(root):
        return
    os.makedirs(SESSIONS, exist_ok=True)
    save(os.path.join(SESSIONS, sid), {"root": root, "start": now_ms()})
    for f in glob.glob(os.path.join(SESSIONS, "*")):
        if time.time() - os.path.getmtime(f) > 7 * 86400:
            os.remove(f)
    try:
        ensure_watcher()
    except Exception:
        pass
    if thor_ready():
        try:
            sync(wait=8)
        except Exception:
            pass
    news = untold(load(INDEX, {"bugs": {}}))
    if news:
        print(json.dumps({"systemMessage": user_summary(news)}))


def hook_post_tool():
    sid, _ = hook_input()
    if not sid or not os.path.exists(os.path.join(SESSIONS, sid)):
        return
    try:
        with Lock(3):
            idx = load(INDEX, {"bugs": {}})
            news = untold(idx)
            if news:
                print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": digest(news)}}))
                for b in news:
                    b.setdefault("local", {})["told"] = tell_key(b)
                save(INDEX, idx)
            mark_told()
    except Busy:
        pass


def install_hooks():
    os.makedirs(os.path.dirname(HOOK_FILE), exist_ok=True)
    hooks = {"hooks": {
        "SessionStart": [{"hooks": [{"type": "command", "command": HOOK_SCRIPT, "args": ["session-start"], "timeout": 30}]}],
        "PostToolUse": [{"hooks": [{"type": "command", "command": HOOK_SCRIPT, "args": ["post-tool"], "timeout": 10}]}],
    }}
    save(HOOK_FILE, hooks)
    return HOOK_FILE


# ---- coro commands ----

def cmd_sync(args):
    dry = C.flag(args, "--dry-run")
    github = not C.flag(args, "--no-github")
    out = sync(github=github, dry=dry)
    if dry:
        idx = load(INDEX, {"bugs": {}})
        print("Dry run: %s on the Thor waiting for the Mac." % plural(len(out["new"]) + len(out["changed"]), "bug"))
        cfg = config()
        for b, proj in out["preview"]:
            print("\n--- %s issue for %s ---\nTitle: %s\n\n%s" % ("update" if (b.get("local") or {}).get("issue") else "new",
                                                              b["id"], issue_title(b, cfg, proj), issue_body(b, cfg, proj)))
        if not out["preview"]:
            print("No GitHub issues would be opened or changed.")
        return
    print(describe(out))


def cmd_bugs(args):
    show_all = C.flag(args, "--all")
    if not C.flag(args, "--local") and thor_ready():
        try:
            sync(github=False, publish_installed=False)
        except Busy:
            pass
    idx = load(INDEX, {"bugs": {}})
    bugs = sorted(idx.get("bugs", {}).values(), key=lambda b: (b.get("state") != "open", -(b.get("last") or 0)))
    if not show_all:
        bugs = [b for b in bugs if b.get("state") == "open"]
    if not bugs:
        print("No %sbugs." % ("" if show_all else "open "))
        return
    for b in bugs:
        L = b.get("local") or {}
        print("%-16s %-7s %-14s %-15s %-12s %3dx  %s  %-5s %s" % (
            b["id"], b.get("state"), one_line(b.get("app") or b.get("pkg") or "Thor", 14), (b.get("versions") or ["?"])[-1][:15],
            KIND_SHORT.get(b.get("kind"), b.get("kind"))[:12], b.get("count") or 1, when(b.get("last")),
            "#%d" % L["issue"] if L.get("issue") else "", one_line(b.get("title"), 90)))


def find_bug(idx, bid):
    b = idx.get("bugs", {}).get(bid)
    if not b:
        hits = [k for k in idx.get("bugs", {}) if k.startswith(bid)]
        if len(hits) == 1:
            b = idx["bugs"][hits[0]]
    if not b:
        raise C.CoroError("no bug %s (coro bugs --all lists them)" % bid)
    return b


def cmd_bug(args):
    if not args:
        raise C.CoroError("usage: coro bug ID [fixed [VERSION] | open | ignore | forget]")
    idx = load(INDEX, {"bugs": {}})
    if thor_ready() and args[0] not in idx.get("bugs", {}):
        try:
            sync(github=False, publish_installed=False)
            idx = load(INDEX, {"bugs": {}})
        except Busy:
            pass
    b = find_bug(idx, args[0])
    action = args[1] if len(args) > 1 else None
    if action:
        state = {"fixed": "fixed", "open": "open", "reopen": "open", "ignore": "ignored", "ignored": "ignored"}.get(action)
        if action == "forget":
            if thor_ready():
                C.bridge("bug-forget", b["id"])
            with Lock(30):
                idx = load(INDEX, {"bugs": {}})
                idx["bugs"].pop(b["id"], None)
                save(INDEX, idx)
            shutil.rmtree(os.path.join(BUGS, b["id"]), ignore_errors=True)
            print("Forgot %s (its GitHub issue, if any, stays)." % b["id"])
            return
        if not state:
            raise C.CoroError("unknown action %s" % action)
        version = args[2] if len(args) > 2 and state == "fixed" else ""
        with Lock(30):
            idx = load(INDEX, {"bugs": {}})
            b = idx["bugs"][b["id"]]
            b["state"] = state
            if state == "fixed":
                b["fixedIn"] = version
            if thor_ready():
                C.need(C.bridge("bug-state", b["id"], state=state, version=version))
            else:
                b.setdefault("local", {})["pendingState"] = {"state": state, "version": version}
            save(INDEX, idx)
        out = sync(publish_installed=False)
        print("%s is now %s%s. %s" % (b["id"], state, " in " + version if version else "", describe(out)))
        return
    L = b.get("local") or {}
    ctx = b.get("context") or {}
    print("%s  %s (%s)  %s%s" % (b["id"], b.get("app") or "the Thor", b.get("pkg") or "-", b.get("state"),
                                 " in " + b["fixedIn"] if b.get("state") == "fixed" and b.get("fixedIn") else ""))
    print(b.get("title"))
    print("Kind: %s.  Seen %s, first %s, last %s.  Versions: %s" % (
        KIND_SHORT.get(b.get("kind"), b.get("kind")), plural(b.get("count") or 1, "time"), when(b.get("first")),
        when(b.get("last")), ", ".join(b.get("versions") or []) or "?"))
    if ctx.get("screens"):
        print("On screen: %s" % ", ".join("%s=%s" % kv for kv in ctx["screens"].items()))
    if ctx.get("driver"):
        print("Vulkan driver: %s" % ctx["driver"])
    if b.get("external"):
        print("Not a Grok Build app: Coronamon caught it because it was on screen.")
    if L.get("issue"):
        print("GitHub: #%d %s (%s)" % (L["issue"], L.get("url", ""), L.get("issueState", "?")))
    if b.get("category") or b.get("note"):
        print("\nThe user reported (%s):\n%s" % (b.get("category") or "", b.get("note") or ""))
    files = [os.path.join(BUGS, b["id"], f) for f in L.get("files") or []]
    if files:
        print("\nFiles:\n" + "\n".join("  " + f.replace(HOME, "~") for f in files))
    if b.get("text"):
        print("\n%s" % b["text"])


def cmd_bug_report(args):
    category = C.opt(args, "--category", "Something else")
    github = C.flag(args, "--github")
    if len(args) < 2:
        raise C.CoroError('usage: coro bug-report PKG "what happened" [--category C] [--github]')
    b = C.need(C.bridge("bug-report", args[0], text=" ".join(args[1:]), category=category, github=github))["bug"]
    print("Saved %s: %s" % (b["id"], b["title"]))


def cmd_publish(args):
    notes = C.opt(args, "--notes")
    summary = C.opt(args, "--summary")
    version = C.opt(args, "--version")
    fixes = C.opt(args, "--fixes")
    push = not C.flag(args, "--no-push")
    dry = C.flag(args, "--dry-run")
    cfg = config()
    names = list(cfg["projects"]) if C.flag(args, "--all") else args
    if not names:
        raise C.CoroError("usage: coro publish PROJECT|--all [--notes N] [--summary S] [--version V] [--fixes IDS] "
                          "[--no-push] [--dry-run]\nprojects: %s" % ", ".join(cfg["projects"]))
    idx = load(INDEX, {"bugs": {}})
    issues = [((idx["bugs"].get(i) or {}).get("local") or {}).get("issue") for i in re.split(r"[,\s]+", fixes or "") if i]
    for name in names:
        print(publish(name, version=version, notes=notes, summary=summary, issues=[n for n in issues if n], push=push, dry=dry))


def cmd_github(args):
    action = args[0] if args else "status"
    cfg = config()
    R = repo_dir(cfg)
    if action in ("on", "off"):
        s = load(SETTINGS, {})
        s["github"] = action == "on"
        save(SETTINGS, s)
        print("GitHub updates are %s." % ("on" if s["github"] else "off: bugs still go to Grok, nothing is posted or pushed"))
    elif action == "push":
        print(push_pending(cfg))
    elif action == "status":
        print("GitHub updates: %s.  Repo: https://github.com/%s  Working copy: %s" % (
            "on" if github_on() else "off", cfg["slug"], R.replace(HOME, "~")))
        print("Commits waiting to push: %d" % unpushed(R))
        pub = load(PUBLISHED, {})
        for name, p in cfg["projects"].items():
            rec = pub.get(name) or {}
            print("  %-22s %-16s %s" % (name, rec.get("version") or "-", ("published " + when(rec["at"])) if rec.get("at") else ""))
    else:
        raise C.CoroError("usage: coro github [status|on|off|push]")


def cmd_watch(args):
    if C.flag(args, "--stop"):
        pid = watcher_pid()
        if pid:
            os.kill(pid, signal.SIGTERM)
        print("Stopped the watcher." if pid else "The watcher isn't running.")
    elif C.flag(args, "--status"):
        pid = watcher_pid()
        print("Watcher: %s" % ("running (pid %d)" % pid if pid else "not running"))
        try:
            print("".join(open(WATCH_LOG).readlines()[-8:]).rstrip())
        except OSError:
            pass
    elif C.flag(args, "--daemon"):
        if watcher_pid():
            return
        if daemonize():
            try:
                watch_loop()
            finally:
                os._exit(0)
    elif C.flag(args, "--run"):
        watch_loop()
    else:
        print("Watcher already running." if not ensure_watcher() else "Started the watcher.")


def cmd_grok_hook(args):
    event = args[0] if args else ""
    try:
        if event == "session-start":
            hook_session_start()
        elif event == "post-tool":
            hook_post_tool()
        elif event == "install":
            print("Wrote %s. Grok Build loads it in new sessions (or /hooks, then r)." % install_hooks())
        else:
            raise C.CoroError("usage: coro grok-hook session-start|post-tool|install")
    except C.CoroError:
        raise
    except Exception:
        if event in ("session-start", "post-tool"):
            return
        raise


COMMANDS = {"sync": cmd_sync, "bugs": cmd_bugs, "bug": cmd_bug, "bug-report": cmd_bug_report, "publish": cmd_publish,
            "github": cmd_github, "watch": cmd_watch, "grok-hook": cmd_grok_hook}
