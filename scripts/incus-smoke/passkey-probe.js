// Executed only in the private browser page through CDP. Never log credentials or responses.
window.tendProbe = (() => {
    const passkey = '/api/firstfactor/passkey';
    const registration = '/api/secondfactor/webauthn/credential/register';
    const elevation = '/api/user/session/elevation';
    const targetURL = 'https://app.garden.internal/cgi-bin/probe';
    async function api(path, method = 'GET', body) {
        const response = await fetch(path, {
            method, credentials: 'include', signal: AbortSignal.timeout(8000),
            headers: { 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        return { status: response.status, body: await response.json() };
    }
    return {
        password: async body => (await api('/api/firstfactor', 'POST', body)).status,
        elevation: async () => (await api(elevation, 'POST')).status,
        verify: async otc => (await api(elevation, 'PUT', { otc })).status,
        register: async () => {
            const options = await api(registration, 'PUT', { description: 'Tend CI passkey' });
            if (options.status !== 200) return { options: options.status, stored: 0, resident: false };
            const credential = await navigator.credentials.create({
                publicKey: PublicKeyCredential.parseCreationOptionsFromJSON(options.body.data.publicKey),
                signal: AbortSignal.timeout(8000)
            });
            const result = await api(registration, 'POST', credential.toJSON());
            return { options: options.status, stored: result.status,
                resident: credential.getClientExtensionResults().credProps?.rk === true };
        },
        login: async mode => {
            const options = await api(passkey);
            if (options.status !== 200) return { status: options.status, stage: 'options' };
            const publicKey = PublicKeyCredential.parseRequestOptionsFromJSON(options.body.data.publicKey);
            // The server keeps its original required-UV challenge. Test rejection of a signed non-UV response.
            if (mode === 'unverified') publicKey.userVerification = 'discouraged';
            try {
                const credential = await navigator.credentials.get({ publicKey, signal: AbortSignal.timeout(4000) });
                const response = credential.toJSON();
                if (mode === 'unknown') {
                    response.id = response.rawId = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
                        .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
                }
                const result = await api(passkey, 'POST', { response, keepMeLoggedIn: false, targetURL });
                return { status: result.status, stage: 'server', ok: result.body.status === 'OK' };
            } catch (error) {
                return { status: 0, stage: 'browser', error: error.name };
            }
        }
    };
})();
true;
