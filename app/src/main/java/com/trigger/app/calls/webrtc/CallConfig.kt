package com.trigger.app.calls.webrtc

/**
 * TURN server config — self-hosted coturn on GCP e2-micro (Always Free tier).
 *
 * Setup (live since 2026-09-23):
 *   - VM: `coturn-server` in us-central1-a (project: trigger-app-cd138)
 *   - Machine type: e2-micro (2 vCPU, 1 GB RAM, 30 GB disk — Always Free)
 *   - Static IP: 34.29.65.144 (reserved as 'coturn-ip')
 *   - Firewall rules:
 *       `allow-turn-3478`  — TCP+UDP port 3478 from 0.0.0.0/0
 *       `allow-turn-relay` — UDP ports 49152-65535 from 0.0.0.0/0
 *   - coturn config at /etc/turnserver.conf:
 *       listening-port=3478, lt-cred-mech, realm=trigger.app,
 *       min-port=49152, max-port=65535
 *
 * Cost: $0/month forever within Always Free limits (1 GB egress/month free,
 * ~$0.085/GB after — ~50 hours of 1-on-1 audio calls per 1 GB).
 *
 * For long-term production with hundreds of concurrent calls, switch to
 * time-limited credentials generated per-call by a Cloud Function that
 * calls coturn's REST API (HMAC-SHA1 of username=expiry:userid +
 * shared secret). Static credentials are fine for the current scale.
 *
 * WHY TURN:
 *   STUN alone (Google's free stun:stun.l.google.com:19302) works for
 *   ~80% of NAT scenarios. The remaining ~20% (symmetric NATs, enterprise
 *   firewalls, some cellular carriers) cannot establish a direct peer-to-
 *   peer connection — they need a TURN relay server to bounce traffic
 *   through. WhatsApp-level reliability requires TURN. Without it,
 *   ~1 in 5 calls silently fail to connect.
 */
object CallConfig {

    data class TurnServer(
        val host: String,
        val port: Int,
        val username: String,
        val credential: String
    )

    /**
     * TURN server — self-hosted coturn on GCP e2-micro (Always Free tier).
     * Static credentials are OK for current scale. For production with
     * many users, switch to time-limited credentials generated per-call
     * by a Cloud Function.
     *
     * To rotate credentials: SSH into coturn-server, edit
     * /etc/turnserver.conf, replace the `user=` line, run
     * `systemctl restart coturn`, then update this file.
     */
    @Volatile
    var turnServer: TurnServer? = TurnServer(
        host = "34.29.65.144",
        port = 3478,
        username = "trigger_1823",
        credential = "5pHp3UW63zQlCTHsvc3A4g"
    )

    /**
     * Maximum call duration in seconds (3600 = 1 hour).
     * Calls will auto-hang-up at this limit to prevent runaway resource
     * usage + billing surprises.
     */
    const val MAX_CALL_DURATION_SECONDS = 3600L
}
