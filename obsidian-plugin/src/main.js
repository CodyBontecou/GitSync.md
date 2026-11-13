"use strict";
const { Plugin, Notice, Platform } = require("obsidian");
const { Connection, ConnectionError, RENEW_KEY } = require("./connection");

module.exports = class GitSyncConnect extends Plugin {
    async onload() {
        this.connection = new Connection(this.app, () => this.app.plugins.getPlugin("obsidian-git"));
        this.registerObsidianProtocolHandler("gitsync-connect", async params => {
            try {
                if (params.vault !== this.app.vault.getName()) return;
                if (params.status === "prepared") {
                    const replace = this.app.loadLocalStorage(RENEW_KEY) === true;
                    this.app.saveLocalStorage(RENEW_KEY, null);
                    await this.connect(replace);
                    return;
                }
                if (params.status !== "ready") throw new Error("GitSync.md could not connect this vault. Open GitSync.md to check the connection.");
                await this.connection.finish(params.requestID);
                new Notice("Obsidian Git is connected to GitSync.md.");
            } catch {
                // Crypto and storage errors can contain implementation data.
                new Notice("Connection could not be completed. Run GitSync.md Connect: Connect this vault to retry.");
            }
        });
        this.addCommand({ id: "connect", name: "Connect this vault", callback: () => this.connect(false, true) });
        this.addCommand({ id: "reconnect", name: "Reconnect this vault (replace existing Git credentials)", callback: () => this.connect(true, true) });
        this.app.workspace.onLayoutReady(() => {
            // Git and this plugin may load in either order. Wait for Git once,
            // without repeatedly opening GitSync.md or prompting the user.
            let attempts = 0;
            const tryConnect = () => {
                if (this.app.plugins.getPlugin("obsidian-git")?.settings) void this.connect();
                else if (++attempts < 30) this.retry = window.setTimeout(tryConnect, 1000);
            };
            tryConnect();
        });
    }
    async connect(replace = false, manual = false) {
        if (!Platform.isMobileApp) {
            if (manual) new Notice("GitSync.md connection is for iPhone and iPad. On desktop, configure Git's credential helper.");
            return;
        }
        try {
            // Manual retries renew the native request (also after token rotation).
            const url = manual ? await this.connection.renew(replace) : await this.connection.begin(replace);
            if (url === "connected") new Notice("Obsidian Git is connected to GitSync.md.");
            else if (url) {
                // iOS can drop a second app launch while Obsidian is still
                // handling the native app's return URL. Let that transition
                // finish before opening the next leg of the handoff.
                window.clearTimeout(this.handoff);
                this.handoff = window.setTimeout(() => {
                    this.handoff = null;
                    window.open(url, "_self");
                }, 750);
            }
            else if (manual) new Notice("No connection is waiting. Clone this vault with GitSync.md while signed into GitHub.");
        } catch (error) {
            // Only our own descriptive errors are shown by begin; never print
            // exceptions or credentials to the developer console.
            new Notice(error instanceof ConnectionError || (error instanceof Error && error.message.startsWith("Obsidian Git already"))
                ? error.message : "Could not start the connection. Enable Obsidian Git and check that this vault was cloned with GitSync.md.");
        }
    }
    onunload() {
        window.clearTimeout(this.retry);
        window.clearTimeout(this.handoff);
    }
};
