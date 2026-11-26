import { gcm } from "./aes-gcm.js";

const form = document.querySelector("form");
const button = form.querySelector("button");
const status = document.getElementById("status");
const fragment = window.location.hash.slice(1);
const hasKey = /^[a-f0-9]{64}$/.test(fragment);
const key = hasKey ? Uint8Array.from(fragment.match(/../g), value => parseInt(value, 16)) : null;

if (key && window.crypto?.getRandomValues) {
    history.replaceState(null, "", window.location.pathname);
    button.disabled = false;
} else {
    status.textContent = "Scan a fresh setup code from your TV.";
}

function base64(bytes) {
    return btoa(String.fromCharCode(...bytes));
}

form.addEventListener("submit", async event => {
    event.preventDefault();
    if (!key || button.disabled) return;
    button.disabled = true;
    status.textContent = "Sending the playlist to your TV…";
    try {
        const nonce = crypto.getRandomValues(new Uint8Array(12));
        const playlist = new TextEncoder().encode(JSON.stringify({
            server: document.getElementById("server").value.trim(),
            username: document.getElementById("username").value.trim(),
            password: document.getElementById("password").value
        }));
        const ciphertext = gcm(key, nonce).encrypt(playlist);
        const response = await fetch("/api/playlist", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ nonce: base64(nonce), ciphertext: base64(ciphertext) }),
            cache: "no-store"
        });
        status.textContent = await response.text();
    } catch {
        status.textContent = "The TV is unreachable. Check that the setup code is still open.";
    } finally {
        button.disabled = false;
    }
});
