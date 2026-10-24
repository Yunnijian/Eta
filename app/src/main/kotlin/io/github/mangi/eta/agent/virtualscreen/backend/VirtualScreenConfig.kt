package io.github.mangi.eta.agent.virtualscreen.backend

internal data class VirtualScreenConfig(
    val width: Int = 720,
    val height: Int = 1600,
    val density: Int = 320,
    val rotation: Int = 0,
    val allowScreenOff: Boolean = false,
    val idleTimeoutMinutes: Int = 20,
) {
    init {
        require(width in 320..4096) { "INVALID_WIDTH" }
        require(height in 480..4096) { "INVALID_HEIGHT" }
        require(density in 120..640) { "INVALID_DENSITY" }
        require(rotation in 0..3) { "INVALID_ROTATION" }
        require(idleTimeoutMinutes in 0..240) { "INVALID_IDLE_TIMEOUT" }
    }
}
