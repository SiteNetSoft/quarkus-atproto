import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import '@vaadin/text-field';
import '@vaadin/button';

/**
 * AT Protocol OAuth: client type, client_id, the served client metadata, active sessions (no tokens) and a sign-in
 * box that opens the login endpoint in a new tab.
 */
export class QwcAtprotoOauth extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 1rem; padding: 1rem; }
        dl { display: grid; grid-template-columns: max-content auto; gap: 0.2rem 0.75rem; margin: 0; }
        dt { color: var(--lumo-contrast-60pct); }
        dd { margin: 0; overflow-wrap: anywhere; }
        pre { background: var(--lumo-contrast-5pct); padding: 0.75rem; overflow: auto; margin: 0; }
        table { border-collapse: collapse; width: 100%; font-size: var(--lumo-font-size-s); }
        th, td { text-align: left; padding: 0.25rem 0.5rem; border-bottom: 1px solid var(--lumo-contrast-10pct); overflow-wrap: anywhere; }
        .row { display: flex; gap: 0.5rem; align-items: flex-end; }
        vaadin-text-field { flex: 1; }
        .muted { color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s); }
        .error { color: var(--lumo-error-text-color); }
        h3 { margin: 0; font-size: var(--lumo-font-size-m); }
    `;

    static properties = {
        _info: { state: true },
        _sessions: { state: true },
        _error: { state: true },
        _handle: { state: true },
    };

    constructor() {
        super();
        this._info = null;
        this._sessions = null;
        this._error = null;
        this._handle = '';
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    _load() {
        this.jsonRpc.info()
            .then(r => { this._info = r.result; })
            .catch(e => { this._error = (e && e.error && e.error.message) || 'info() failed'; });
        this.jsonRpc.sessions()
            .then(r => { this._sessions = r.result; })
            .catch(e => { this._error = (e && e.error && e.error.message) || 'sessions() failed'; });
    }

    render() {
        if (this._error) {
            return html`<span class="error">${this._error}</span>`;
        }
        const i = this._info;
        if (!i) {
            return html`<span>Loading...</span>`;
        }
        return html`
            <dl>
                <dt>Client</dt><dd>${i.clientType}</dd>
                <dt>client_id</dt><dd><code>${i.clientId}</code></dd>
                <dt>Redirect URI</dt><dd><code>${i.redirectUri}</code></dd>
                <dt>Scope</dt><dd><code>${i.scope}</code></dd>
                <dt>Mode</dt><dd>${i.mode}</dd>
            </dl>
            <h3>Sign in</h3>
            <div class="row">
                <vaadin-text-field label="Handle, DID or server URL" placeholder="alice.bsky.social"
                    .value=${this._handle}
                    @value-changed=${e => { this._handle = e.detail.value; }}
                    @keydown=${e => { if (e.key === 'Enter') this._signIn(); }}></vaadin-text-field>
                <vaadin-button theme="primary" ?disabled=${!this._handle} @click=${this._signIn}>Sign in</vaadin-button>
            </div>
            ${i.mode === 'link' ? html`<span class="muted">Link mode: sign in to the application first.</span>` : ''}
            <h3>Sessions <vaadin-button theme="tertiary small" @click=${this._load}>Refresh</vaadin-button></h3>
            ${this._renderSessions(i)}
            <h3>Client metadata</h3>
            <pre>${JSON.stringify(i.metadata, null, 2)}</pre>
        `;
    }

    _renderSessions(i) {
        if (!i.sessionsListed) {
            return html`<span class="muted">Sessions live in the application's store and are not listed here.</span>`;
        }
        const s = this._sessions;
        if (!s) {
            return html`<span>Loading...</span>`;
        }
        if (s.length === 0) {
            return html`<span class="muted">No active sessions.</span>`;
        }
        return html`
            <table>
                <thead><tr><th>DID</th><th>Handle</th><th>PDS</th><th>Scope</th><th>Access token expires</th></tr></thead>
                <tbody>${s.map(r => html`
                    <tr><td>${r.did}</td><td>${r.handle}</td><td>${r.pds}</td><td>${r.scope}</td><td>${r.accessExpiry}</td></tr>`)}
                </tbody>
            </table>`;
    }

    _signIn() {
        const i = this._info;
        if (!i || !this._handle) {
            return;
        }
        window.open(i.loginPath + '?handle=' + encodeURIComponent(this._handle.trim()), '_blank', 'noopener');
    }
}
customElements.define('qwc-atproto-oauth', QwcAtprotoOauth);
