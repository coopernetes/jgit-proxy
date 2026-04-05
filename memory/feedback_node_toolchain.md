---
name: Node toolchain — use mise
description: Use mise to manage Node.js; never invoke node/npm directly without mise
type: feedback
---

Use `mise` to manage Node.js. Don't call `node` or `npm` directly assuming a system install.

**Why:** Node is managed via mise, not a system package. Running bare `node`/`npm` will fail or pick up the wrong version.

**How to apply:** Run `mise ls` to see what's installed. Use `mise install node@<version>` to install, `mise use node@<version>` to activate. Then prefix commands or rely on mise shims.
