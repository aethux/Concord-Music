# concord/ — the project's own documents

The app is the directory above this one. This folder holds only paperwork, and it is the folder every
agent should read before touching code.

| file | what it is |
| --- | --- |
| [`AGENTS.md`](AGENTS.md) | the rules: hard rules, ask-don't-decide, reuse before write, one PR per thing |
| [`PLAN.md`](PLAN.md) | the architecture, the phases, the decisions and what they cost |
| [`STATE.md`](STATE.md) | what is built, what is proven, what is deferred — read this first |
| [`IDEA.md`](IDEA.md) | the one-line origin of the project |
| [`engine-port.md`](engine-port.md) | every part of Mocha's `applesync/` as it came across, step by step, and the divergences |
| [`apple-surface.md`](apple-surface.md) | every Apple Music web endpoint, id shape and attribute read off the signed-in page |
| [`menu-inventory.md`](menu-inventory.md) | every menu item in Concord and in Cider's 4.0.40 build, side by side |
| [`screenshots/`](screenshots/INDEX.md) | 25 Mocha + 18 Mochy screens, indexed |
| [`tools/`](tools/) | `shots.py` (adb screenshot driver), `apple_probe.py` (CDP against the signed-in page), `apple_sweep.py` (walk the web app surface by surface) |

What the app is, what it will and will not do, and the licence all live in the root `README.md` and
root `CREDITS.md` — where a person looks for them. Neither is copied here, so there is one version of
each.

Start with `STATE.md`. Its "Next action" is the only thing that should be started next.
