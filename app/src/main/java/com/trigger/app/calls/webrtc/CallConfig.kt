package com.trigger.app.calls.webrtc

/**
 * User-configured TURN server credentials + endpoints for the WebRTC calls.
 *
 * WHY TURN:
 *   STUN (Google's free stun.l.google.com:19302) works for ~80% of NAT
 *   scenarios. The remaining ~20% (symmetric NATs, enterprise firewalls,
 *   some cellular carriers) cannot establish a direct peer-to-peer
 *   connection — they need a TURN relay server to bounce traffic through.
 *
 *   WhatsApp-level reliability requires TURN. Without it, ~1 in 5 calls
 *   will silently fail to connect.
 *
 * OPTIONS (pick one):
 *
 *   Option A — Twilio Network Traversal Service (recommended):
 *     - Pay-per-GB, ~$0.40/GB for the first 1TB, cheaper after.
 *     - Reliable, globally distributed, well-documented.
 *     - Requires: TWILIO_ACCOUNT_SID + TWILIO_AUTH_TOKEN
 *     - The client calls a Cloud Function (getCallTurnCredentials) which
 *       generates short-lived (1-hour) TURN credentials using the Twilio
 *       API + writes them to calls/{callID}/turnCredentials. This file
 *       just reads from there.
 *
 *   Option B — Self-hosted coturn on a VPS:
 *     - Free software, ~$5/month VPS.
 *     - You manage the server, security, scaling.
 *     - Requires: TURN_HOST, TURN_PORT, TURN_USERNAME, TURN_CREDENTIAL
 *       (static long-lived credentials — less secure).
 *
 *   Option C — Cloudflare STUN/TURN:
 *     - Free tier available for some users.
 *     - Requires: Cloudflare account + integration.
 *
 * WHAT TO PROVIDE TO ME:
 *   For Twilio: account SID + auth token (from twilio.com/console).
 *   I'll wire up the Cloud Function that generates short-lived credentials
 *   + the client reads from calls/{callID}/turnCredentials on call start.
 *
 *   For self-hosted coturn: TURN host + port + static username + password.
 *   I'll hard-code them in the ICE_SERVERS list in WebRtcCallSession.
 *
 *   Until one of these is provided, calls will use STUN-only — works
 *   fine on the same WiFi or non-symmetric NATs, but will fail silently
 *   for ~20% of real-world scenarios.
 */
object CallConfig {

    data class TurnServer(
        val host: String,
        val port: Int,
        val username: String,
        val credential: String
    )

    /**
     * Set this at app startup (e.g., in App.onCreate()) by reading from
     * Firebase Remote Config, a Cloud Function response, or hard-coded
     * values for dev.
     *
     * Null = STUN-only (no TURN). Calls will work on ~80% of networks.
     */
    @Volatile
    var turnServer: TurnServer? = null

    /**
     * Maximum call duration in seconds (3600 = 1 hour).
     * Calls will auto-hang-up at this limit to prevent runaway resource
     * usage + billing surprises.
     */
    const val MAX_CALL_DURATION_SECONDS = 3600L
}
