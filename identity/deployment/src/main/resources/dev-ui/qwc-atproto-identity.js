import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import '@vaadin/text-field';
import '@vaadin/button';

/**
 * Identity lookup: enter a handle (as a user would type it) or a DID, see the verified identity or the error.
 */
export class QwcAtprotoIdentity extends LitElement {
    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 1rem; padding: 1rem; }
        .row { display: flex; gap: 0.5rem; align-items: flex-end; }
        vaadin-text-field { flex: 1; }
        pre { background: var(--lumo-contrast-5pct); padding: 0.75rem; overflow: auto; }
        .settings { color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s); }
    `;

    static properties = {
        _settings: { state: true },
        _identifier: { state: true },
        _result: { state: true },
        _busy: { state: true },
    };

    constructor() {
        super();
        this._settings = null;
        this._identifier = 'bsky.app';
        this._result = '';
        this._busy = false;
    }

    connectedCallback() {
        super.connectedCallback();
        this.jsonRpc.settings().then(r => { this._settings = r.result; });
    }

    render() {
        const s = this._settings;
        return html`
            ${s ? html`<div class="settings">Cache TTL <code>${s.cacheTtl}</code> · DNS <code>${s.dnsServers}</code>
                · well-known overrides <code>${s.wellKnownOverrides}</code></div>` : ''}
            <div class="row">
                <vaadin-text-field label="Handle or DID" .value=${this._identifier}
                    @value-changed=${e => { this._identifier = e.detail.value; }}
                    @keydown=${e => { if (e.key === 'Enter') this._lookup(); }}></vaadin-text-field>
                <vaadin-button theme="primary" ?disabled=${this._busy} @click=${this._lookup}>Resolve</vaadin-button>
            </div>
            ${this._result ? html`<pre>${this._result}</pre>` : ''}
        `;
    }

    _lookup() {
        this._busy = true;
        this.jsonRpc.lookup({ identifier: this._identifier })
            .then(r => { this._result = JSON.stringify(r.result, null, 2); })
            .finally(() => { this._busy = false; });
    }
}
customElements.define('qwc-atproto-identity', QwcAtprotoIdentity);
