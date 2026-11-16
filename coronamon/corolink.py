"""Coronamon's link between the AYN Thor, Grok Build and GitHub. The coro script loads it.

Bugs: Coronamon keeps a bug log on the Thor (crashes, freezes and native crashes it catches,
problems the Grok-built apps report themselves, and reports from its Report a problem button).
sync takes whatever changed into ~/thor-coronamon/bugs/, opens or updates a GitHub issue for each
bug in a project that is on GitHub (personal details taken out), and tells the Thor which bugs
Grok has, with their issue numbers.

Updates: publish copies a project's files into the GitHub working copy, adds the notes to the
project's README history and the top-level timeline, commits "Update <app> to <version>" with
"Fixes #N" for the bugs it fixes, and pushes. coro install publishes every app update it installs.

Grok: grok-hook runs from Grok Build's hooks where Grok Build runs them. A session in a Thor folder
starts the watcher (which syncs whenever the Thor is plugged in; `coro watch --install` also runs it
from a login agent), new bugs are handed to the agent once, and the turn hooks record which Thor
sessions are in the middle of a turn.

Automatic runs: each time the Thor is plugged in, the watcher looks for Grok Build on the Mac (a
session open, signed in, online) and tells the Thor what it found; once Grok Build is ready it runs
Coronamon's update check. When there are new bugs in the user's own apps, user reports, or updates
that need a new build of the user's projects (and installable updates if the user set Coronamon's
updates to auto), it starts a headless Grok Build run that fixes them and ships the fixes with
coro install (which publishes them), once no Grok Build session has been in use for 10 minutes and no
game or video is in front. The run's report goes back to the Thor, to a Mac notification and to the
next Grok Build session.

The Den's Grok Build page: buttons on the Thor for coro autofix status/hold/on/off/stop, coro watch
--stop and diagnostics. adb only goes from the Mac to the Thor, so Coronamon keeps the requests and
bumps /sdcard/Coronamon/control.rev; while the Thor is plugged in, the watcher checks that file every
3 seconds, does what was asked and answers over the bridge with the result and a fresh status.
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
import threading
import time
import uuid

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


def digest(news, runs=(), limit=12):
    lines = []
    for kind, r in runs:
        if kind == "started":
            lines.append("Coronamon started an automatic Grok Build run at %s for %s. It works on the Thor, so don't install "
                         "apps or drive the Thor's screens until it's done unless the user asks "
                         "(`~/thor-coronamon/coro autofix status`; `coro autofix stop` stops it)." % (
                             hm(r["started"]), items_short(r.get("items") or [], 8)))
        else:
            lines.append("Coronamon's automatic Grok Build run from %s %s at %s: %s (its session: `grok --resume %s`)" % (
                hm(r["started"]), "finished" if r.get("ok") else "stopped", hm(r.get("ended")), r["summary"], r["session"]))
    if runs:
        lines.append("Mention this to the user when it fits.")
    if not news:
        return "\n".join(lines)
    if lines:
        lines.append("")
    auto = af_setting("autofix", True)
    st = af_state() if auto else {}
    running = {i["key"] for i in (st.get("current") or {}).get("items") or []}
    lines.append("Coronamon (the AYN Thor companion) found %s since Grok last heard. Bug log: ~/thor-coronamon/bugs/" %
                 plural(len(news), "new problem"))
    for b in news[:limit]:
        L = b.get("local") or {}
        v = (b.get("versions") or [""])[-1]
        bits = [KIND_SHORT.get(b.get("kind"), b.get("kind")),
                "seen %d times" % b["count"] if (b.get("count") or 1) > 1 else None,
                "last " + when(b.get("last")),
                "GitHub #%d" % L["issue"] if L.get("issue") else None,
                "came back after a fix" if b.get("reopened") else None,
                "not a Grok Build app" if b.get("external") else None,
                "the automatic run is on it" if "bug:" + b["id"] in running else None]
        lines.append("- %s %s%s: %s (%s)" % (b["id"], b.get("app") or b.get("pkg") or "the Thor", " " + v if v else "",
                                             one_line(b.get("title"), 160), ", ".join(x for x in bits if x)))
        if b.get("kind") == "report" and b.get("note"):
            lines.append("  The user wrote: " + one_line(b["note"], 300))
    if len(news) > limit:
        lines.append("- and %d more (coro bugs)" % (len(news) - limit))
    first = ("Tell the user about these. While the Thor is plugged in, Coronamon hands bugs in the user's own apps and "
             "the user's reports to an automatic Grok Build run once no Grok Build session has been in use for 10 "
             "minutes, so leave those to it unless the user asks; to handle one here, run "
             "`~/thor-coronamon/coro autofix hold` first so the run waits. Offer to fix anything else. "
             if auto else "Tell the user about these and offer to fix them. ")
    lines += ["", first + "`~/thor-coronamon/coro bug <id>` shows the details, the report and the log paths. After a fix, "
                  "install it with `coro install <apk> --fixes <id> --notes \"<what changed>\"`: that marks the bug fixed on "
                  "the Thor, closes its GitHub issue and publishes the update."]
    return "\n".join(lines)


def untold_runs():
    """Automatic runs the Grok Build sessions haven't heard about: the one going now, and ones that ended lately."""
    st = af_state()
    told = st.get("told") or {}
    out = []
    cur = st.get("current")
    if cur and not cur.get("test") and cur["id"] not in told:
        out.append(("started", cur))
    for r in st["runs"]:
        if not r.get("test") and told.get(r["id"]) != "done" and now_ms() - (r.get("ended") or 0) < 3 * 86400_000:
            out.append(("done", r))
    return out


def mark_runs_told(runs):
    with AfState() as st:
        told = st.get("told") or {}
        for kind, r in runs:
            told[r["id"]] = kind
        keep = {r["id"] for r in st["runs"]} | ({st["current"]["id"]} if st.get("current") else set())
        st["told"] = {k: v for k, v in told.items() if k in keep}


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
    if os.path.exists(LAUNCH_AGENT) and launchctl("kickstart", "gui/%d/%s" % (os.getuid(), LAUNCH_LABEL)).returncode == 0:
        return True
    subprocess.Popen([sys.executable, CORO, "watch", "--daemon"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                     stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True).wait(timeout=15)
    return True


LAUNCH_LABEL = "dev.grokbuild.coronamon.watch"
LAUNCH_AGENT = os.path.join(HOME, "Library", "LaunchAgents", LAUNCH_LABEL + ".plist")


def launchctl(*args):
    return subprocess.run(["launchctl"] + list(args), capture_output=True, text=True, timeout=30)


def login_path():
    """The login shell's PATH, so runs started from launchd find git, gh and the build tools."""
    try:
        out = subprocess.run(["/bin/zsh", "-lc", 'printf %s "$PATH"'], capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.TimeoutExpired):
        out = ""
    keep = []
    for p in out.split(":") + [os.path.join(GROK_HOME, "bin"), os.path.dirname(GH), os.path.dirname(C.ADB),
                               "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"]:
        if p and p not in keep and "/.grok/runtime/" not in p and "cursor" not in p.lower() and os.path.isdir(p):
            keep.append(p)
    return ":".join(keep)


def install_launch_agent():
    """Runs the watcher at login and keeps it running, so the Thor is checked each time it's plugged in."""
    import plistlib
    os.makedirs(os.path.dirname(LAUNCH_AGENT), exist_ok=True)
    plist = {"Label": LAUNCH_LABEL, "ProgramArguments": ["/usr/bin/python3", CORO, "watch", "--run", "--wait"],
             "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 30, "WorkingDirectory": HOME,
             "EnvironmentVariables": {"PATH": login_path(), "LANG": "en_US.UTF-8"},
             "StandardOutPath": os.path.join(STATE, "watch.launchd.log"),
             "StandardErrorPath": os.path.join(STATE, "watch.launchd.log")}
    with open(LAUNCH_AGENT, "wb") as f:
        plistlib.dump(plist, f)
    domain = "gui/%d" % os.getuid()
    launchctl("bootout", "%s/%s" % (domain, LAUNCH_LABEL))
    r = launchctl("bootstrap", domain, LAUNCH_AGENT)
    if r.returncode != 0:
        raise C.CoroError("launchctl bootstrap failed: %s" % (r.stderr or r.stdout).strip())


def launch_agent_loaded():
    return launchctl("print", "gui/%d/%s" % (os.getuid(), LAUNCH_LABEL)).returncode == 0


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


def watch_loop(wait=False):
    os.makedirs(STATE, exist_ok=True)
    only = os.open(WATCH_PID + ".lock", os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(only, fcntl.LOCK_EX | (0 if wait else fcntl.LOCK_NB))
    except OSError:
        return
    with open(WATCH_PID, "w") as f:
        f.write(str(os.getpid()))
    signal.signal(signal.SIGTERM, lambda *a: (wlog("stopped"), os._exit(0)))
    code = (os.path.getmtime(__file__), os.path.getmtime(CORO))
    wlog("watching for the Thor (pid %d)" % os.getpid())
    threading.Thread(target=control_loop, name="control", daemon=True).start()
    connected, last_rev, last_check, last_sync, last_tick = False, None, 0.0, 0.0, 0.0
    while True:
        try:
            if (os.path.getmtime(__file__), os.path.getmtime(CORO)) != code:
                wlog("coro changed; restarting")
                os.execv(sys.executable, [sys.executable, CORO, "watch", "--run"] + (["--wait"] if wait else []))
            if thor_state(timeout=10) == "device":
                reason, fresh = None, False
                if not connected:
                    connected, fresh = True, True
                    wlog("Thor plugged in")
                    time.sleep(3)
                    _watch["connected"] = True
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
                if reason or time.time() - last_tick > 60:
                    last_tick = time.time()
                    try:
                        grok_tick(fresh)
                    except Exception as e:
                        wlog("grok check failed: %s" % e)
                    push_mac_status(force=fresh)
            else:
                if connected:
                    connected = False
                    _watch["connected"], _watch["pushed"] = False, None
                    wlog("Thor unplugged")
                if time.time() - last_tick > 60:
                    last_tick = time.time()
                    reap_run()
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


def check_thor_updates(wait=75):
    """Coronamon's GitHub release check, now instead of on its 12-hour timer. Only new versions are announced."""
    start = now_ms()
    try:
        C.need(C.bridge("check", "", retries=3, announce="false"))
    except Exception as e:
        wlog("update check failed: %s" % e)
        return None
    apps, deadline = None, time.time() + wait
    while time.time() < deadline:
        time.sleep(3)
        try:
            apps = C.need(C.bridge("registry", retries=3))["apps"]
        except Exception:
            continue
        if not [a for a in apps if a.get("github") and a.get("updateMode") != "off" and (a.get("lastCheck") or 0) < start]:
            break
    if apps:
        ups = ["%s %s" % (a.get("label"), a["update"]["version"]) for a in apps if (a.get("update") or {}).get("version")]
        wlog("update check: %s" % ((", ".join(ups) + " available") if ups else "everything is up to date"))
    return apps


# ---- Grok Build on this Mac, and the runs that fix what the Thor reports ----

GROK_HOME = os.environ.get("GROK_HOME") or os.path.join(HOME, ".grok")
GROK = os.path.join(GROK_HOME, "bin", "grok")
GROK_SESSIONS = os.path.join(GROK_HOME, "agent-host", "sessions")
AUTOFIX = os.path.join(STATE, "autofix.json")
RUNS = os.path.join(STATE, "autofix")
ONE_OFF = {"-p", "--single", "--prompt-file", "--prompt-json", "login", "logout", "update", "host-server",
           "--version", "-V", "--help", "-h", "help"}
AUTOFIX_RULES = ("Coronamon started this run by itself and nobody is watching it live. Follow the task's rules exactly: "
                 "never print, commit or upload secrets; stop if the internet goes away; never uninstall apps, clear app "
                 "data or force-push; don't change apps the user didn't make beyond installing verified updates.")
AUTOFIX_DENY = ["Read(**/keys/**)", "Read(**/*.pass)", "Read(**/*.jks)", "Read(**/*.keystore)", "Read(**/dev/.token)",
                "Read(**/.grok/auth.json)", "Read(**/prod.keys)", "Read(**/title.keys)", "Bash(*git push*--force*)",
                "Bash(*git push -f*)", "Bash(*pm uninstall*)", "Bash(*adb*uninstall*)", "Bash(*pm clear*)", "Bash(sudo *)"]
STOPPED = {"offline": "Paused because the Mac went offline; it picks up again once the internet is back.",
           "timeout": "Stopped after running out of time.", "stopped": "Stopped from the Mac.",
           "stopped-thor": "Stopped from the Thor.", "lost": "The run stopped unexpectedly."}

AUTOFIX_PROMPT = """\
Coronamon started this Grok Build run by itself: the AYN Thor was plugged into the Mac while Grok Build was open, and \
Coronamon has things that need fixing or updating. Nobody is watching live. The user sees the results afterwards in \
Coronamon's Activity tab and notifications on the Thor, in a Mac notification, and on the GitHub page.

To do ({when}):
{items}

How to handle them (`coro` is ~/thor-coronamon/coro):
- First read the workspace memory topics for these apps and for Coronamon (the memory index lists them), and check \
the internet. If the internet goes away at any point, stop (the user's rule: pause all development until Wi-Fi is \
back) and write the report.
{how}
- Apps the user didn't make can't be changed: never change their settings or data. If an update doesn't resolve \
their problem, leave it for the user.
- If a fix is unclear or risky, or needs a big change, don't ship it. Leave the bug open and explain what you found \
and what you'd suggest.

On the Thor (adb is `~/platform-tools/adb -s 62dff1b0`):
- Check `coro status` first. Don't install anything or touch the screens while a game or video is in front; if \
that doesn't change, finish what doesn't need the Thor and report the rest.
- Run `coro hide 60` (or `coro shot ... --hide`) before taps and screenshots, and leave both screens on CrossBar \
when you're done.
- If the Thor gets unplugged, don't ship builds you couldn't test.

Rules: never print, commit or upload secrets (keys/ folders, *.pass, keystores, dev/.token, auth tokens, prod.keys \
or title.keys), and check which files every commit holds. Nothing sensitive goes to public websites. Never type into \
login fields, don't bypass the network's TLS filter or macOS privacy prompts, and never uninstall apps, clear app \
data or force-push. Update the memory topics when you learn something lasting.

When you're done, write this JSON to {report}:
{{"summary": "<one or two plain sentences for the user: what you fixed, installed or left, and why>",
 "items": [{{"key": "<the item's key in brackets above>", "result": "fixed|installed|left|failed", "note": "<one sentence>"}}]}}
Write it even if you couldn't do anything, then end with the same summary.
"""

HOW_BUGS = """\
- Bugs in the user's own apps (the projects in ~/thor-coronamon/github.json): `coro bug ID` shows the details and \
where the report, logs and screenshots are. Find the cause, fix it in the project's source folder, build, install a \
test build with `coro install APK --no-publish`, and check the fix on the Thor. Then ship one build per app with \
`coro install APK --notes "<what changed, for the user>" --fixes <bug ids>`: that marks the bugs fixed on the Thor, \
closes their GitHub issues and publishes the update with its README History and Timeline entries (the changelog)."""
HOW_REPORTS = """\
- The user's own reports from the Thor ("user report") can be about any app: do what the report asks if it's clear \
and safe; otherwise explain in the report what you found."""
HOW_INSTALL = """\
- Updates Coronamon can install (listed because the user set Coronamon's updates setting to auto): make sure the app \
isn't running (`adb shell pidof PKG`), run `coro update PKG` (Coronamon downloads the release, checks its SHA-256, \
package and signature, and installs it), and confirm the new version with \
`adb shell dumpsys package PKG | grep versionName` (it can take a minute; `coro history 5` shows the install). Then \
open the app briefly to check it starts, and go back to CrossBar. After updating Shizuku, run `coro setup`. Apps the \
user didn't make never go on GitHub; the Thor's log is enough."""
HOW_REBUILD = """\
- Updates that need a new build of one of the user's projects: follow that project's memory topic to rebuild, test \
and ship it with `coro install --notes`. Game and emulator files never go on GitHub."""


def autofix_prompt(cfg, items, report):
    how = [HOW_BUGS] if [i for i in items if i["kind"] == "bug" and i["project"]] else []
    how += [HOW_REPORTS] if [i for i in items if i["kind"] == "bug" and i["what"] == KIND_SHORT["report"]] else []
    how += [HOW_INSTALL] if [i for i in items if i["kind"] == "update" and i["canInstall"]] else []
    how += [HOW_REBUILD] if [i for i in items if i["kind"] == "update" and not i["canInstall"]] else []
    return AUTOFIX_PROMPT.format(when=time.strftime("%b %d %H:%M"), report=report, how="\n".join(how),
                                 items="\n".join(item_line(cfg, i) for i in items))


AUTOFIX_TEST = """\
This is a short test of Coronamon's automatic Grok Build runs, started with `coro autofix test`. Don't change any \
files and don't touch the Thor. Run `~/thor-coronamon/coro autofix status` once, then write this JSON to {report}:
{{"summary": "Test run OK: Coronamon started Grok Build and got its report back.", "items": []}}
Then end with that summary.
"""


class AfState:
    """Read-modify-write of autofix.json under its own lock, since a run holds no coro lock."""

    def __enter__(self):
        os.makedirs(STATE, exist_ok=True)
        self.fd = os.open(AUTOFIX + ".lock", os.O_RDWR | os.O_CREAT, 0o600)
        fcntl.flock(self.fd, fcntl.LOCK_EX)
        self.st = af_state()
        return self.st

    def __exit__(self, kind, *rest):
        try:
            if kind is None:
                save(AUTOFIX, self.st)
        finally:
            fcntl.flock(self.fd, fcntl.LOCK_UN)
            os.close(self.fd)


def af_state():
    st = load(AUTOFIX, {})
    st.setdefault("handled", {})
    st.setdefault("runs", [])
    return st


def af_setting(key, default):
    return load(SETTINGS, {}).get(key, default)


def hm(ms):
    return time.strftime("%H:%M", time.localtime(ms / 1000)) if ms else "?"


def pid_alive(pid):
    try:
        pid = int(pid)
        try:
            if os.waitpid(pid, os.WNOHANG)[0] == pid:
                return False
        except ChildProcessError:
            pass
        os.kill(pid, 0)
        return True
    except PermissionError:
        return True
    except (OSError, TypeError, ValueError):
        return False


def reap_run():
    """Finishes a run whose runner died without reporting, so its items are tried again."""
    cur = af_state().get("current")
    if cur and not pid_alive(cur.get("pid")) and now_ms() - cur["started"] > 120_000:
        gp = cur.get("grokPid")
        try:
            args = subprocess.run(["ps", "-o", "args=", "-p", str(gp)], capture_output=True, text=True, timeout=10).stdout
            if gp and cur["session"] in args:
                os.kill(gp, signal.SIGINT)
        except (OSError, subprocess.TimeoutExpired):
            pass
        finish_run(cur["id"], None, "lost")
        return True
    return False


def grok_processes():
    """Grok Build sessions open on this Mac: grok processes other than headless runs and one-off commands."""
    try:
        out = subprocess.run(["ps", "-axo", "pid=,args="], capture_output=True, text=True, timeout=15).stdout
    except (OSError, subprocess.TimeoutExpired):
        return []
    pids = []
    for line in out.splitlines():
        argv = line.split()
        if len(argv) < 2:
            continue
        name = os.path.basename(argv[1])
        if name != "grok" and not name.startswith("agent-host") and not (argv[1].startswith(GROK_HOME + "/bin/") and name != "node"):
            continue
        if ONE_OFF.intersection(a.split("=")[0] for a in argv[2:]):
            continue
        pids.append(int(argv[0]))
    return pids


def online():
    """Both services a run needs answer, xAI's API and GitHub. Any HTTP reply counts."""
    for url in ("https://api.x.ai/", "https://api.github.com/"):
        try:
            r = subprocess.run(["/usr/bin/curl", "-sS", "-o", "/dev/null", "-m", "8", "-w", "%{http_code}", url],
                               capture_output=True, text=True, timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            return False
        if r.stdout.strip() in ("", "000"):
            return False
    return True


def grok_status():
    """Whether Grok Build can take work: a session open on this Mac, signed in, and online."""
    procs = grok_processes()
    try:
        signed_in = bool(os.environ.get("XAI_API_KEY")) or os.path.getsize(os.path.join(GROK_HOME, "auth.json")) > 0
    except OSError:
        signed_in = False
    why = None
    if not procs:
        why = "Grok Build isn't open on the Mac"
    elif not signed_in:
        why = "Grok Build isn't signed in"
    elif not online():
        why = "the Mac is offline"
    return {"sessions": len(procs), "signedIn": signed_in, "ok": why is None, "why": why}


def busy_sessions(quiet=600):
    """Grok Build sessions the user is working in: any session other than Coronamon's own runs whose storage changed
    in the last 10 minutes (Grok Build doesn't run user hooks everywhere, so this doesn't rely on them), and sessions
    the turn hooks mark as mid-turn where they do run."""
    busy = []
    for f in glob.glob(os.path.join(SESSIONS, "*.busy")):
        sid = os.path.basename(f)[:-5]
        turns = os.path.exists(os.path.join(SESSIONS, sid + ".turns"))
        if time.time() - os.path.getmtime(f) < (6 * 3600 if turns else 1200):
            busy.append(sid)
    st = af_state()
    own = {r.get("session") for r in st["runs"]} | {(st.get("current") or {}).get("session")}
    try:
        sids = os.listdir(GROK_SESSIONS)
    except OSError:
        sids = []
    for sid in sids:
        if sid in own:
            continue
        d = os.path.join(GROK_SESSIONS, sid)
        try:
            changed = max(os.path.getmtime(os.path.join(d, f)) for f in os.listdir(d))
        except (OSError, ValueError):
            continue
        if time.time() - changed < quiet:
            busy.append(sid)
    return sorted(set(busy))


def thor_in_use():
    """The game or video in front on either screen (Coronamon's avoid list), if any."""
    try:
        s = C.need(C.bridge("status", retries=3))
    except Exception:
        return None
    for stage in s.get("stages") or []:
        if stage.get("avoid") and stage.get("top"):
            return stage["top"]
    return None


def tell_thor_grok(state, msg, detail=None, ok=True):
    if not thor_ready():
        return False
    try:
        return bool(C.bridge("grok", state, retries=3, msg=one_line(msg, 90), detail=detail, ok=ok).get("ok"))
    except Exception:
        return False


def tell_thor_run(r):
    """A finished run, for the Thor. A stop someone asked for isn't trouble, so it isn't reported as trouble
    (Coronamon builds from before the "stopped" state only take "done")."""
    if r.get("stopped") in ("stopped", "stopped-thor") and tell_thor_grok("stopped", r["summary"], detail=r["summary"], ok=False):
        return True
    return tell_thor_grok("done", r["summary"], detail=r["summary"], ok=r["ok"])


def bug_mark(b):
    """What makes a bug new again for a run: a newer version, or coming back after a fix (not repeats)."""
    return "%s|%s|%s" % (b.get("state"), (b.get("versions") or [""])[-1], b.get("reopened") or 0)


def pending_items(cfg, idx, apps, auto_updates=False):
    """What a run can act on: open bugs in the user's own projects, the user's own reports, updates that need a new
    build of one of the user's projects, and installable updates only if the user set Coronamon's updates to auto
    (with "ask" he asks on the Thor, and that answer is the user's)."""
    items = []
    for b in sorted(idx.get("bugs", {}).values(), key=lambda b: b.get("last") or 0, reverse=True):
        if b.get("state") != "open":
            continue
        proj = project_for_pkg(cfg, b.get("pkg")) or issue_project(b, cfg)
        if not proj and b.get("kind") != "report":
            continue
        L = b.get("local") or {}
        items.append({"key": "bug:" + b["id"], "mark": bug_mark(b), "kind": "bug", "id": b["id"],
                      "app": b.get("app") or b.get("pkg") or "the Thor", "pkg": b.get("pkg") or "",
                      "version": (b.get("versions") or [""])[-1], "project": proj,
                      "what": KIND_SHORT.get(b.get("kind"), b.get("kind") or "problem"), "title": one_line(b.get("title"), 200),
                      "count": b.get("count") or 1, "last": b.get("last"), "issue": L.get("issue"),
                      "reopened": b.get("reopened") or 0, "note": one_line(b.get("note"), 400) if b.get("kind") == "report" else ""})
    for a in sorted(apps or [], key=lambda a: a.get("label") or ""):
        up = a.get("update") or {}
        if not up.get("version") or not a.get("installed") or a.get("updateMode") == "off":
            continue
        can = bool(up.get("url")) and a.get("updateMode") != "notify"
        proj = project_for_pkg(cfg, a["pkg"])
        if not (auto_updates if can else proj):
            continue
        items.append({"key": "update:" + a["pkg"], "mark": up["version"], "kind": "update", "pkg": a["pkg"],
                      "app": a.get("label") or a["pkg"], "have": a.get("versionName") or "", "version": up["version"],
                      "github": a.get("github") or "", "project": proj, "canInstall": can})
    return items


def new_items(st, items):
    return [i for i in items if st["handled"].get(i["key"]) != i["mark"]]


def item_short(i):
    return "the %s %s" % (i["app"], "update" if i["kind"] == "update" else i["what"])


def items_short(items, n=3):
    names = [item_short(i) for i in items[:n]] + (["%d more" % (len(items) - n)] if len(items) > n else [])
    if len(names) < 2:
        return names[0] if names else "nothing"
    return ", ".join(names[:-1]) + " and " + names[-1]


def item_line(cfg, i):
    if i["kind"] == "update":
        how = ("Coronamon can install it: `coro update %s`." % i["pkg"]) if i["canInstall"] else \
            "It needs a new Grok build; Coronamon can't install this one by itself."
        return "- [%s] Update: %s (%s) %s -> %s is out (github.com/%s). %s" % (
            i["key"], i["app"], i["pkg"], i["have"] or "?", i["version"], i["github"], how)
    p = cfg["projects"].get(i["project"]) if i["project"] else None
    bits = ["seen %d times" % i["count"] if i["count"] > 1 else None, "last " + when(i["last"]),
            "GitHub issue #%d" % i["issue"] if i["issue"] else None, "came back after a fix" if i["reopened"] else None]
    where = (" Project: %s, source in %s." % (p["label"], p["src"])) if p else " Not one of the user's projects."
    line = "- [%s] Bug %s: %s%s (%s), %s: %s (%s).%s Details: `coro bug %s`." % (
        i["key"], i["id"], i["app"], " " + i["version"] if i["version"] else "", i["pkg"] or "the Thor itself", i["what"],
        i["title"], ", ".join(x for x in bits if x), where, i["id"])
    return line + (' The user wrote: "%s"' % i["note"] if i["note"] else "")


def run_budget(st):
    """Why a run can't start yet, if the last one just ended or there were already enough today."""
    runs = [r for r in st["runs"] if not r.get("test")]
    if runs and now_ms() - (runs[-1].get("ended") or 0) < 10 * 60_000:
        return "the last run ended less than 10 minutes ago"
    per_day = af_setting("autofixPerDay", 6)
    if len([r for r in runs if now_ms() - (r.get("started") or 0) < 86400_000]) >= per_day:
        return "it already ran %d times in the last day" % per_day
    return None


def start_run(items, why, test=False):
    run = time.strftime("%Y%m%d-%H%M%S")
    d = os.path.join(RUNS, run)
    os.makedirs(d, exist_ok=True)
    report = os.path.join(d, "report.json")
    prompt = AUTOFIX_TEST.format(report=report) if test else autofix_prompt(config(), items, report)
    with open(os.path.join(d, "prompt.md"), "w") as f:
        f.write(prompt)
    with AfState() as st:
        if st.get("current"):
            raise C.CoroError("a run is already going (%s)" % st["current"]["id"])
        st["current"] = {"id": run, "session": str(uuid.uuid4()), "started": now_ms(), "why": why, "test": test,
                         "items": items, "dir": d}
        st["waiting"] = None
    with open(os.path.join(d, "runner.log"), "a") as log:
        subprocess.Popen([sys.executable, CORO, "autofix", "--runner", run], stdin=subprocess.DEVNULL, stdout=log,
                         stderr=log, cwd=HOME, start_new_session=True, close_fds=True)
    wlog("autofix %s started (%s): %s" % (run, why, "a test" if test else items_short(items, 8)))
    return run


def autofix_runner(run):
    """The process that runs one headless Grok Build session and reports how it went."""
    cur = af_state().get("current") or {}
    if cur.get("id") != run:
        return
    with AfState() as st:
        st["current"]["pid"] = os.getpid()
    d, items, test = cur["dir"], cur.get("items") or [], cur.get("test")
    tell_thor_grok("fixing", "a quick test" if test else items_short(items),
                   detail="A test run." if test else "Working on %s." % items_short(items, 8))
    touch_news()
    env = {k: v for k, v in os.environ.items()
           if k == "GROK_HOME" or not k.startswith(("GROK_", "CURSOR_", "__CURSOR", "CLAUDE_", "AGENT_TRANSCRIPTS"))}
    env["CORONAMON_AUTOFIX"] = run
    cmd = [GROK, "--prompt-file", os.path.join(d, "prompt.md"), "--cwd", HOME, "--session-id", cur["session"],
           "--always-approve", "--output-format", "json", "--max-turns", str(af_setting("autofixTurns", 400)),
           "--no-auto-update", "--rules", AUTOFIX_RULES]
    for rule in AUTOFIX_DENY:
        cmd += ["--deny", rule]
    with open(os.path.join(d, "result.json"), "w") as out, open(os.path.join(d, "grok.log"), "w") as err:
        p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=err, cwd=HOME, env=env)
    with AfState() as st:
        st["current"]["grokPid"] = p.pid
    deadline = time.time() + 60 * af_setting("autofixMinutes", 120)
    stopped, offline, net_at = None, 0, time.time()
    while True:
        try:
            code = p.wait(timeout=15)
            break
        except subprocess.TimeoutExpired:
            pass
        stop = (af_state().get("current") or {}).get("stop")
        if stop:
            stopped = "stopped-thor" if stop == "thor" else "stopped"
        elif time.time() > deadline:
            stopped = "timeout"
        elif time.time() - net_at > 60:
            net_at = time.time()
            offline = 0 if online() else offline + 1
            if offline >= 3:
                stopped = "offline"
        if stopped:
            p.send_signal(signal.SIGINT)
            try:
                code = p.wait(timeout=120)
            except subprocess.TimeoutExpired:
                p.kill()
                code = p.wait()
            break
    finish_run(run, code, stopped)


def finish_run(run, code, stopped=None):
    with AfState() as st:
        cur = st.get("current") or {}
        if cur.get("id") != run:
            return
        d, items = cur["dir"], cur.get("items") or []
        rep = load(os.path.join(d, "report.json"), None)
        rep = rep if isinstance(rep, dict) else {}
        res = load(os.path.join(d, "result.json"), None)
        res = res if isinstance(res, dict) else {}
        summary = one_line(rep.get("summary"), 600)
        ok = bool(summary) and code == 0 and not stopped
        if stopped:
            summary = (STOPPED[stopped] + " " + summary).strip()
        elif not summary:
            summary = "The run ended without a report (exit %s)." % code
        results = {x.get("key"): x for x in rep.get("items") or [] if isinstance(x, dict)}
        if not cur.get("test") and stopped not in ("offline", "stopped", "stopped-thor", "lost"):
            for i in items:
                st["handled"][i["key"]] = i["mark"]
        rec = {"id": run, "session": res.get("sessionId") or cur["session"], "started": cur["started"],
               "ended": now_ms(), "why": cur.get("why"), "test": bool(cur.get("test")), "ok": ok, "exit": code,
               "stopped": stopped, "summary": summary, "turns": res.get("num_turns"), "toldThor": False,
               "items": [{"key": i["key"], "title": item_short(i), "result": (results.get(i["key"]) or {}).get("result"),
                          "note": one_line((results.get(i["key"]) or {}).get("note"), 300)} for i in items]}
        st["runs"] = (st["runs"] + [rec])[-40:]
        st["current"] = None
    if tell_thor_run(rec):
        with AfState() as st:
            for r in st["runs"]:
                if r["id"] == run:
                    r["toldThor"] = True
        push_mac_status(force=True)
    notify_mac("Grok Build %s: %s" % ("finished" if ok else "stopped", summary))
    how = "done" if ok else {"stopped-thor": "stopped"}.get(stopped, stopped) or "failed (exit %s)" % code
    wlog("autofix %s %s: %s" % (run, how, summary))
    touch_news()


_tick = {"apps": None, "appsAt": 0.0, "auto": False, "grok": None, "grokAt": 0.0, "procs": None}


def thor_apps(T, apps=None):
    """Coronamon's registry, and whether the user lets him install updates by himself."""
    try:
        T["apps"] = apps if apps is not None else C.need(C.bridge("registry", retries=3))["apps"]
        T["auto"] = C.need(C.bridge("setting", retries=3)).get("updates") == "auto"
        T["appsAt"] = time.time()
    except Exception:
        pass


def grok_tick(fresh=False):
    """The watcher calls this while the Thor is plugged in. It tells the Thor whether Grok Build is ready; once it is,
    it runs Coronamon's update check, and starts a run when there's something new for one and nothing is in the way."""
    T = _tick
    reap_run()
    st = af_state()
    cur = st.get("current")
    for r in st["runs"]:
        if r.get("toldThor") is False and tell_thor_run(r):
            with AfState() as s:
                for x in s["runs"]:
                    if x["id"] == r["id"]:
                        x["toldThor"] = True
    procs = len(grok_processes())
    if fresh or procs != T["procs"] or time.time() - T["grokAt"] > (60 if st.get("waiting") else 300):
        T["grok"], T["grokAt"], T["procs"] = grok_status(), time.time(), procs
    g = T["grok"]
    seen = "connected" if g["ok"] else "away: " + g["why"]
    newly = fresh or seen != st.get("thorGrok")
    if newly and not cur:
        msg = "%s open on the Mac" % plural(g["sessions"], "Grok Build session") if g["ok"] else g["why"]
        if tell_thor_grok("connected" if g["ok"] else "away", msg, detail=msg + "."):
            with AfState() as s:
                s["thorGrok"] = seen
    if newly and g["ok"]:
        thor_apps(T, check_thor_updates())
    elif fresh or time.time() - T["appsAt"] > 600:
        thor_apps(T)
    if cur or not af_setting("autofix", True):
        return
    items = new_items(st, pending_items(config(), load(INDEX, {"bugs": {}}), T["apps"], T["auto"]))
    if not items:
        if st.get("waiting"):
            with AfState() as s:
                s["waiting"] = None
        return
    hold = st.get("holdUntil") or 0
    wait = "on hold until %s" % hm(hold) if hold > now_ms() else g["why"]
    if not wait and busy_sessions():
        wait = "a Grok Build session was in use in the last 10 minutes"
    if not wait:
        front = thor_in_use()
        if front:
            label = next((a.get("label") for a in T["apps"] or [] if a.get("pkg") == front), front)
            wait = "%s is open on the Thor" % label
    wait = wait or run_budget(st)
    if wait:
        if wait != st.get("waiting"):
            wlog("autofix waiting (%s): %s" % (items_short(items, 8), wait))
            with AfState() as s:
                s["waiting"] = wait
        return
    try:
        start_run(items, "plugged in" if fresh else "new on the Thor")
    except C.CoroError as e:
        wlog("autofix: %s" % e)


# ---- the Den's Grok Build page: buttons on the Thor for the commands above ----

CONTROL_REV = "/sdcard/Coronamon/control.rev"
CONTROL_NAMES = {"status": "check status", "hold": "pause runs", "resume": "resume runs", "off": "turn runs off",
                 "on": "turn runs on", "stop-run": "stop the current run", "watch-stop": "stop the watcher",
                 "diagnostics": "run diagnostics"}
_watch = {"connected": False, "pushed": None, "pushedAt": 0.0, "pushError": None}


def control_loop():
    """The Thor can't reach the Mac, so the Den's buttons leave requests with Coronamon and bump control.rev.
    While the Thor is plugged in, this checks that file every 3 seconds and answers what's waiting."""
    last = None
    while True:
        time.sleep(3)
        if not _watch["connected"]:
            last = None
            continue
        try:
            rev = C.sh("cat %s 2>/dev/null" % CONTROL_REV, timeout=15).strip()
            if rev and rev != last:
                handle_controls()
                last = rev
        except Exception as e:
            wlog("requests from the Thor failed: %s" % e)
            time.sleep(30)


def handle_controls():
    reqs = C.need(C.bridge("control", "take", retries=3)).get("requests") or []
    stop = False
    for r in reqs:
        action = r.get("action") or ""
        try:
            res = run_control(action, r.get("arg"))
        except Exception as e:
            res = {"ok": False, "msg": "That didn't work on the Mac: %s" % one_line(str(e), 200)}
        try:
            res.setdefault("status", mac_status(fresh=action == "status"))
        except Exception as e:
            wlog("status for the Thor failed: %s" % e)
        C.need(C.bridge("control-done", r.get("id"), retries=3, result=res))
        if res.get("status"):
            _watch["pushed"], _watch["pushedAt"] = status_key(res["status"]), time.time()
        if action != "status":
            wlog("from the Thor, %s: %s" % (CONTROL_NAMES.get(action, action), res.get("msg")))
        stop = stop or (action == "watch-stop" and res.get("ok"))
    if stop:
        stop_watching()


def run_control(action, arg=None):
    """One request from the Den: {ok, msg}, plus checks for diagnostics."""
    if action == "status":
        return {"ok": True, "msg": "Up to date."}
    if action in ("hold", "resume"):
        minutes = 0
        if action == "hold":
            try:
                minutes = max(1, min(24 * 60, int(float(arg or 60))))
            except ValueError:
                minutes = 60
        until = now_ms() + minutes * 60_000 if minutes else 0
        with AfState() as st:
            st["holdUntil"] = until
            going = bool(st.get("current"))
        if not until:
            return {"ok": True, "msg": "Automatic runs can start again."}
        return {"ok": True, "msg": "Automatic runs are paused for %s.%s" % (
            "1 hour" if minutes == 60 else plural(minutes, "minute"), " The run going now keeps going." if going else "")}
    if action in ("on", "off"):
        s = load(SETTINGS, {})
        s["autofix"] = action == "on"
        save(SETTINGS, s)
        going = action == "off" and bool(af_state().get("current"))
        return {"ok": True, "msg": "Automatic runs are %s.%s" % (action, " The run going now keeps going." if going else "")}
    if action == "stop-run":
        with AfState() as st:
            cur = st.get("current")
            if cur:
                cur["stop"] = "thor"
        return {"ok": True, "msg": "Asked the run to stop. It saves its work and reports back within a minute or two."
                if cur else "No run was going."}
    if action == "watch-stop":
        return {"ok": True, "msg": "The watcher stopped. It starts again when you log in to the Mac, or run coro watch there."}
    if action == "diagnostics":
        checks = mac_diagnostics()
        look = [c["name"] for c in checks if c["state"] in ("warn", "bad")]
        return {"ok": True, "checks": checks, "msg": "%s to look at: %s." % (plural(len(look), "thing"), ", ".join(look))
                if look else "Everything on the Mac looks good."}
    return {"ok": False, "msg": "The Mac doesn't know how to do that (%s)." % action}


def stop_watching():
    """coro watch --stop from inside the watcher, when the Thor asks."""
    wlog("stopping (asked from the Thor)")
    if launch_agent_loaded():
        launchctl("bootout", "gui/%d/%s" % (os.getuid(), LAUNCH_LABEL))
        time.sleep(10)
    os.kill(os.getpid(), signal.SIGTERM)


def agent_state():
    if launch_agent_loaded():
        return "loaded"
    return "installed" if os.path.exists(LAUNCH_AGENT) else "none"


def proc_started(pid):
    try:
        out = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True, timeout=10,
                             env=dict(os.environ, LC_ALL="C")).stdout.strip()
        return int(time.mktime(time.strptime(out, "%a %b %d %H:%M:%S %Y")) * 1000)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return 0


def watch_log_tail(n):
    try:
        return [line.rstrip("\n") for line in open(WATCH_LOG, errors="replace").readlines()[-n:]]
    except OSError:
        return []


def mac_status(fresh=False):
    """What the Den's Grok Build page shows: coro autofix status and coro watch --status, as data."""
    T = _tick
    g = T["grok"] if T["grok"] and not fresh else grok_status()
    st = af_state()
    cur, hold = st.get("current"), st.get("holdUntil") or 0
    items = new_items(st, pending_items(config(), load(INDEX, {"bugs": {}}), T["apps"], T["auto"]))
    real = [r for r in st["runs"] if not r.get("test")]
    pid = watcher_pid()
    return {
        "t": now_ms(),
        "watcher": {"pid": pid or 0, "since": proc_started(pid) if pid else 0, "agent": agent_state()},
        "grok": {"ok": g["ok"], "sessions": g["sessions"], "signedIn": g["signedIn"], "why": g["why"],
                 "busy": len(busy_sessions())},
        "autofix": {
            "on": bool(af_setting("autofix", True)), "perDay": af_setting("autofixPerDay", 6),
            "minutes": af_setting("autofixMinutes", 120), "holdUntil": hold if hold > now_ms() else 0,
            "waiting": None if cur else st.get("waiting"),
            "current": {"id": cur["id"], "started": cur["started"], "test": bool(cur.get("test")),
                        "what": "a test" if cur.get("test") else items_short(cur.get("items") or [], 8),
                        "stopping": bool(cur.get("stop"))} if cur else None,
            "today": len([r for r in real if now_ms() - (r.get("started") or 0) < 86400_000]),
            "next": [item_short(i) for i in items[:8]], "nextMore": max(0, len(items) - 8),
            "runs": [{"started": r.get("started"), "ended": r.get("ended"), "test": bool(r.get("test")),
                      "ok": bool(r.get("ok")), "stopped": r.get("stopped"), "summary": one_line(r.get("summary"), 300)}
                     for r in st["runs"][-5:][::-1]],
        },
        "log": watch_log_tail(8),
    }


def status_key(s):
    return json.dumps({k: v for k, v in s.items() if k not in ("t", "log")}, sort_keys=True)


def push_mac_status(force=False):
    """Keeps the Den's Grok Build page current: whenever something changes, and every 2 minutes so it can tell
    the watcher is there."""
    try:
        s = mac_status()
        key = status_key(s)
        if not force and key == _watch["pushed"] and time.time() - _watch["pushedAt"] < 120:
            return
        C.need(C.bridge("mac-status", retries=3, status=s))
        _watch["pushed"], _watch["pushedAt"], _watch["pushError"] = key, time.time(), None
    except Exception as e:
        if str(e) != _watch["pushError"]:
            wlog("status for the Thor failed: %s" % e)
        _watch["pushError"] = str(e)


def finding(name, state, detail, fix=None):
    """One diagnostics line; state is good, warn, bad or info."""
    return {"name": name, "state": state, "detail": detail, "fix": fix}


def watch_errors(hours=24):
    """Lines in the watcher's log from the last day that say something went wrong."""
    try:
        lines = open(WATCH_LOG, errors="replace").readlines()
    except OSError:
        return []
    now, year, out = time.time(), time.localtime().tm_year, []
    for line in lines:
        try:
            t = time.mktime(time.strptime("%d-%s" % (year, line[:14]), "%Y-%m-%d %H:%M:%S"))
        except ValueError:
            continue
        if now - hours * 3600 <= t <= now + 60 and re.search(r"\berror\b|failed|didn't answer", line, re.I):
            out.append(line.rstrip("\n"))
    return out


def adb_state():
    """The Thor's line in adb devices: device, unauthorized, offline, or None when it isn't there."""
    try:
        lines = subprocess.run([C.ADB, "devices"], capture_output=True, text=True, timeout=15).stdout.splitlines()
    except (OSError, subprocess.TimeoutExpired):
        return None
    return next((line.split()[1] for line in lines if line.startswith(C.SERIAL + "\t")), None)


def mac_diagnostics():
    """The Mac's half of the Den's Run diagnostics button, and of coro diagnostics."""
    out = []
    pid, agent = watcher_pid(), agent_state()
    if not pid:
        out.append(finding("Watcher", "bad", "Not running, so nothing happens when the Thor is plugged in.",
                           "On the Mac, run coro watch --install."))
    elif agent != "loaded":
        out.append(finding("Watcher", "warn", "Running, but not from the login agent, so it won't come back if it stops.",
                           "On the Mac, run coro watch --install."))
    else:
        since = proc_started(pid)
        out.append(finding("Watcher", "good", "Running%s, and it starts at login." % (" since " + when(since) if since else "")))

    state = adb_state() if os.path.exists(C.ADB) else "no adb"
    if state == "device":
        t0 = time.time()
        try:
            v = C.need(C.bridge("ping", retries=2)).get("version")
            out.append(finding("Link to the Thor", "good", "Plugged in over USB; Coronamon %s answers in %.1f s." % (v, time.time() - t0)))
        except Exception as e:
            out.append(finding("Link to the Thor", "bad", "Plugged in, but Coronamon didn't answer: %s" % one_line(str(e), 120),
                               "Open Coronamon on the Thor, or run coro setup on the Mac."))
    elif state == "unauthorized":
        out.append(finding("Link to the Thor", "bad", "Plugged in, but the Thor hasn't allowed this Mac yet.",
                           "Unlock the Thor and allow USB debugging for this Mac."))
    elif state == "no adb":
        out.append(finding("Link to the Thor", "bad", "adb is missing (%s)." % C.ADB.replace(HOME, "~"),
                           "Unpack Android platform-tools into ~/platform-tools."))
    elif state:
        out.append(finding("Link to the Thor", "warn", "adb sees the Thor as %s." % state, "Unplug the Thor and plug it in again."))
    else:
        out.append(finding("Link to the Thor", "info", "Not plugged in."))

    net = online()
    if not os.path.exists(GROK):
        out.append(finding("Grok Build", "bad", "Not installed (%s)." % GROK.replace(HOME, "~"), "Install Grok Build on the Mac."))
    else:
        try:
            ver = subprocess.run([GROK, "--version"], capture_output=True, text=True, timeout=20).stdout.split("(")[0].strip()
        except (OSError, subprocess.TimeoutExpired):
            ver = ""
        ver = ver or "Grok Build"
        g = grok_status()
        if g["ok"]:
            out.append(finding("Grok Build", "good", "%s: %s open, signed in and online." % (ver, plural(g["sessions"], "session"))))
        else:
            fix = {"Grok Build isn't open on the Mac": "Open Grok Build on the Mac.",
                   "Grok Build isn't signed in": "Sign in to Grok Build on the Mac.",
                   "the Mac is offline": "Check the Mac's Wi-Fi."}.get(g["why"])
            out.append(finding("Grok Build", "warn", "%s: %s, so automatic runs wait." % (ver, g["why"]), fix))
    out.append(finding("Internet", "good", "xAI and GitHub both answer.") if net else
               finding("Internet", "bad", "xAI or GitHub didn't answer, so runs and publishing wait.", "Check the Mac's Wi-Fi."))

    st = af_state()
    cur, hold = st.get("current"), st.get("holdUntil") or 0
    real = [r for r in st["runs"] if not r.get("test")]
    last = real[-1] if real else None
    if cur:
        if not pid_alive(cur.get("pid")) and now_ms() - cur["started"] > 120_000:
            out.append(finding("Automatic runs", "bad", "Run %s is marked as going, but its process is gone." % cur["id"],
                               "The watcher cleans it up within a minute; if it doesn't, run coro autofix status on the Mac."))
        else:
            out.append(finding("Automatic runs", "info", "Running now, since %s: %s." % (
                hm(cur["started"]), "a test" if cur.get("test") else items_short(cur.get("items") or [], 4))))
    elif not af_setting("autofix", True):
        out.append(finding("Automatic runs", "warn", "Off, so new bugs wait for you.", "Turn runs on (Grok Build page), or coro autofix on."))
    elif hold > now_ms():
        out.append(finding("Automatic runs", "info", "Paused until %s." % hm(hold)))
    elif last and not last.get("ok") and last.get("stopped") not in ("stopped", "stopped-thor", "offline"):
        out.append(finding("Automatic runs", "warn", "On, but the last run didn't finish: %s" % one_line(last.get("summary"), 160),
                           "coro autofix log on the Mac shows what happened."))
    else:
        today = len([r for r in real if now_ms() - (r.get("started") or 0) < 86400_000])
        out.append(finding("Automatic runs", "good", "On; %d of %d runs used in the last day%s." % (
            today, af_setting("autofixPerDay", 6), ". Waiting: " + st["waiting"] if st.get("waiting") else "")))

    cfg = config()
    if not github_on():
        out.append(finding("GitHub", "info", "Publishing is off: bugs still go to Grok, nothing is posted.", "coro github on"))
    elif not os.path.exists(GH):
        out.append(finding("GitHub", "bad", "gh is missing (%s), so updates can't be published." % GH.replace(HOME, "~")))
    elif not net:
        out.append(finding("GitHub", "info", "Can't check while the Mac is offline."))
    else:
        try:
            signed = subprocess.run([GH, "auth", "status"], capture_output=True, text=True, timeout=30).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            signed = False
        R = repo_dir(cfg)
        waiting = unpushed(R) if os.path.isdir(R) else 0
        if not signed:
            out.append(finding("GitHub", "bad", "gh isn't signed in, so updates can't be published.", "On the Mac, run gh auth login."))
        elif waiting:
            out.append(finding("GitHub", "warn", "%s waiting to push." % plural(waiting, "commit"), "coro github push"))
        else:
            out.append(finding("GitHub", "good", "Signed in, and everything is pushed to %s." % cfg["slug"]))

    errors = watch_errors()
    if errors:
        out.append(finding("Watcher log", "warn", "%s in the last day. Latest: %s" % (plural(len(errors), "problem"), one_line(errors[-1], 160)),
                           "coro watch --status on the Mac shows the log."))
    else:
        out.append(finding("Watcher log", "good", "No problems in the last day."))
    gb = shutil.disk_usage(HOME).free / 1e9
    out.append(finding("Mac storage", "bad" if gb < 5 else "warn" if gb < 15 else "good", "%.0f GB free." % gb,
                       "Free up space on the Mac; builds need a few GB." if gb < 15 else None))
    return out


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


def autofix_session(sid):
    """The headless session an automatic run started. It gets its work from its prompt, not from the hooks."""
    cur = af_state().get("current") or {}
    return bool(os.environ.get("CORONAMON_AUTOFIX")) or (
        bool(cur.get("session")) and sid == re.sub(r"[^A-Za-z0-9_.-]", "_", cur["session"]))


def hook_session_start():
    sid, root = hook_input()
    if not sid or not thor_workspace(root) or autofix_session(sid):
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
    cur = af_state().get("current")
    msgs = [user_summary(news)] if news else []
    if cur and not cur.get("test"):
        msgs.append("Coronamon: Grok Build is working on %s on the Thor right now." % items_short(cur.get("items") or []))
    if msgs:
        print(json.dumps({"systemMessage": " ".join(msgs)}))


def hook_post_tool():
    sid, _ = hook_input()
    if not sid or not os.path.exists(os.path.join(SESSIONS, sid)):
        return
    try:
        with Lock(3):
            idx = load(INDEX, {"bugs": {}})
            news = untold(idx)
            runs = untold_runs()
            if news or runs:
                print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse",
                                                         "additionalContext": digest(news, runs)}}))
                for b in news:
                    b.setdefault("local", {})["told"] = tell_key(b)
                save(INDEX, idx)
                if runs:
                    mark_runs_told(runs)
            mark_told()
    except Busy:
        pass


def install_hooks():
    """Session start and tool calls for Coronamon's news; prompts and turn ends to know which sessions are mid-turn."""
    os.makedirs(os.path.dirname(HOOK_FILE), exist_ok=True)

    def run(arg, timeout=5, matcher=None):
        entry = {"hooks": [{"type": "command", "command": HOOK_SCRIPT, "args": [arg], "timeout": timeout}]}
        if matcher:
            entry["matcher"] = matcher
        return [entry]
    hooks = {"hooks": {
        "SessionStart": run("session-start", 30),
        "UserPromptSubmit": run("prompt"),
        "PostToolUse": run("post-tool", 10),
        "Stop": run("turn-end"),
        "StopFailure": run("turn-end"),
        "StopCancelled": run("turn-end"),
        "Notification": run("turn-end", matcher="idle_prompt"),
        "SessionEnd": run("session-end"),
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
        agent = launch_agent_loaded()
        if agent:
            launchctl("bootout", "gui/%d/%s" % (os.getuid(), LAUNCH_LABEL))
        pid = watcher_pid()
        if pid:
            os.kill(pid, signal.SIGTERM)
        print("Stopped the watcher%s." % (" until the next login (coro watch starts it again)" if agent else "")
              if pid or agent else "The watcher isn't running.")
    elif C.flag(args, "--install"):
        pid = watcher_pid()
        if pid:
            os.kill(pid, signal.SIGTERM)
        install_launch_agent()
        print("Installed %s: the watcher now starts at login and restarts if it stops." % LAUNCH_AGENT)
    elif C.flag(args, "--uninstall"):
        launchctl("bootout", "gui/%d/%s" % (os.getuid(), LAUNCH_LABEL))
        try:
            os.remove(LAUNCH_AGENT)
            print("Removed the login agent; Grok Build sessions still start the watcher.")
        except OSError:
            print("The login agent wasn't installed.")
    elif C.flag(args, "--status"):
        pid = watcher_pid()
        agent = {"loaded": "loaded", "installed": "installed, not loaded", "none": "not installed"}[agent_state()]
        print("Watcher: %s; login agent %s" % ("running (pid %d)" % pid if pid else "not running", agent))
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
        watch_loop(wait=C.flag(args, "--wait"))
    else:
        print("Watcher already running." if not ensure_watcher() else "Started the watcher.")


def cmd_grok_hook(args):
    event = args[0] if args else ""
    try:
        if event == "session-start":
            hook_session_start()
        elif event == "post-tool":
            hook_post_tool()
        elif event in ("prompt", "turn-end", "session-end"):
            pass  # grok-hook.sh keeps the busy markers itself
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


def autofix_items():
    T = {"apps": None, "auto": False}
    if thor_ready():
        thor_apps(T)
    return pending_items(config(), load(INDEX, {"bugs": {}}), T["apps"], T["auto"]), T["apps"] is not None


def print_items(items, cfg=None):
    cfg = cfg or config()
    for i in items:
        print("  " + item_line(cfg, i)[2:])


def cmd_autofix(args):
    sub = args[0] if args else "status"
    if sub == "--runner":
        autofix_runner(args[1])
    elif sub in ("on", "off"):
        s = load(SETTINGS, {})
        s["autofix"] = sub == "on"
        save(SETTINGS, s)
        print("Automatic Grok Build runs are %s." % sub)
        if sub == "on":
            items, full = autofix_items()
            todo = new_items(af_state(), items)
            if todo:
                print("The next run picks up these (coro autofix skip KEY... leaves one out):")
                print_items(todo)
            if not full:
                print("(Plug in the Thor to include app updates.)")
    elif sub == "status":
        print(autofix_status())
    elif sub == "run":
        dry, now = C.flag(args, "--dry-run"), C.flag(args, "--now")
        items, full = autofix_items()
        todo = new_items(af_state(), items) if not C.flag(args, "--all") else items
        if not todo:
            print("Nothing new for a run%s." % ("" if full else " (the Thor isn't plugged in, so updates weren't checked)"))
            return
        if dry:
            print(autofix_prompt(config(), todo, "<run folder>/report.json"))
            return
        st = af_state()
        g = grok_status()
        wait = None if now else (g["why"] or ("a Grok Build session was in use in the last 10 minutes" if busy_sessions() else None)
                                 or run_budget(st))
        if not now and not wait and thor_ready():
            front = thor_in_use()
            wait = "%s is open on the Thor" % front if front else None
        if wait:
            raise C.CoroError("not starting: %s (add --now to start anyway)" % wait)
        if not online():
            raise C.CoroError("not starting: the Mac is offline")
        print("Started run %s: %s. coro autofix status shows how it's going." % (start_run(todo, "asked from the Mac"),
                                                                                  items_short(todo, 8)))
    elif sub == "test":
        if not online():
            raise C.CoroError("not starting: the Mac is offline")
        print("Started test run %s. coro autofix status shows how it's going." % start_run([], "test", test=True))
    elif sub == "stop":
        with AfState() as st:
            if not st.get("current"):
                print("No run is going.")
                return
            st["current"]["stop"] = True
        print("Asked the run to stop; it saves its session and reports back within a minute or two.")
    elif sub == "hold":
        arg = args[1] if len(args) > 1 else "60"
        with AfState() as st:
            st["holdUntil"] = 0 if arg == "off" else now_ms() + int(float(arg) * 60_000)
            until = st["holdUntil"]
        print("Automatic runs %s." % ("can start again" if not until else "wait until %s" % hm(until)))
    elif sub == "skip":
        keys = args[1:]
        if not keys:
            raise C.CoroError("usage: coro autofix skip KEY... | all (coro autofix status lists the keys)")
        items, _ = autofix_items()
        pick = [i for i in items if "all" in keys or i["key"] in keys or i["key"].split(":", 1)[1] in keys]
        with AfState() as st:
            for i in pick:
                st["handled"][i["key"]] = i["mark"]
        print("Left out of automatic runs: %s." % (", ".join(i["key"] for i in pick) or "nothing matched"))
    elif sub == "log":
        st = af_state()
        runs = ([st["current"]] if st.get("current") else []) + st["runs"][::-1]
        r = next((x for x in runs if not args[1:] or x["id"] == args[1]), None)
        if not r:
            print("No runs yet.")
            return
        d = os.path.join(RUNS, r["id"])
        print("Run %s (%s), session %s" % (r["id"], r.get("why"), r.get("session")))
        if r.get("summary"):
            print("Summary: " + r["summary"])
        for x in r.get("items") or []:
            print("  %s: %s%s" % (x["key"], x.get("result") or "?", " (%s)" % x["note"] if x.get("note") else ""))
        for name in ("runner.log", "grok.log"):
            try:
                tail = open(os.path.join(d, name), errors="replace").readlines()[-15:]
            except OSError:
                continue
            if tail:
                print("--- %s ---\n%s" % (name, "".join(tail).rstrip()))
        print("Folder: %s" % d)
    else:
        raise C.CoroError("usage: coro autofix [status|on|off|run [--dry-run] [--now]|test|stop|hold [MINUTES|off]|"
                          "skip KEY...|log [ID]]")


def autofix_status():
    st = af_state()
    on = af_setting("autofix", True)
    out = ["Automatic runs: %s (up to %d minutes and %d turns each, %d a day)" % (
        "on" if on else "off", af_setting("autofixMinutes", 120), af_setting("autofixTurns", 400),
        af_setting("autofixPerDay", 6))]
    g = grok_status()
    out.append("Grok Build: %s" % ("%s open, signed in, online" % plural(g["sessions"], "session") if g["ok"] else g["why"]))
    busy = busy_sessions()
    if busy:
        out.append("In use: %s (active in the last 10 minutes)" % plural(len(busy), "Grok Build session"))
    cur = st.get("current")
    if cur:
        out.append("Running now: %s since %s%s: %s%s" % (
            cur["id"], hm(cur["started"]), " (pid %s)" % cur["pid"] if cur.get("pid") else "",
            "a test" if cur.get("test") else items_short(cur.get("items") or [], 8), ", stopping" if cur.get("stop") else ""))
    hold = st.get("holdUntil") or 0
    if hold > now_ms():
        out.append("On hold until %s" % hm(hold))
    elif st.get("waiting") and not cur:
        out.append("Waiting: %s" % st["waiting"])
    items, full = autofix_items()
    todo = new_items(st, items)
    if todo:
        out.append("New for the next run:")
        cfg = config()
        out += ["  " + item_line(cfg, i)[2:] for i in todo]
    elif not cur:
        out.append("Nothing new for a run%s." % ("" if full else " (plug in the Thor to check updates)"))
    if st["runs"]:
        out.append("Last runs:")
        for r in st["runs"][-5:][::-1]:
            how = "finished" if r.get("ok") else (r.get("stopped") or "failed").replace("stopped-thor", "stopped from the Thor")
            out.append("  %s %s%s: %s" % (when(r["started"]), "test " if r.get("test") else "", how, r["summary"]))
    return "\n".join(out)


def cmd_diagnostics(args):
    marks = {"good": "ok", "warn": "!!", "bad": "XX", "info": "--"}

    def show(title, checks):
        print(title)
        for c in checks:
            print("  %s %-18s %s" % (marks.get(c.get("state"), "  "), c.get("name"), c.get("detail")))
            if c.get("fix"):
                print("     %-18s fix: %s" % ("", c["fix"]))

    show("Mac", mac_diagnostics())
    if not thor_ready():
        print("Thor\n  -- not plugged in")
        return
    try:
        show("Thor", C.need(C.bridge("diagnostics", retries=3))["checks"])
    except Exception as e:
        print("Thor\n  XX Coronamon didn't run its checks: %s" % e)


COMMANDS = {"sync": cmd_sync, "bugs": cmd_bugs, "bug": cmd_bug, "bug-report": cmd_bug_report, "publish": cmd_publish,
            "github": cmd_github, "watch": cmd_watch, "grok-hook": cmd_grok_hook, "autofix": cmd_autofix,
            "diagnostics": cmd_diagnostics}
