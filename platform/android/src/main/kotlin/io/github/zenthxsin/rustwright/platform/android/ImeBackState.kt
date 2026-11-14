package io.github.zenthxsin.rustwright.platform.android

/** UI-thread state: an IME hide animation may still report visible after consuming Back. */
internal class ImeBackState {
    var visible = false
        private set
    private var dismissed = false

    fun visibilityChanged(visible: Boolean) {
        this.visible = visible
        if (!visible) dismissed = false
    }

    fun inputRequested() { dismissed = false }

    fun inputEnded() {
        visible = false
        dismissed = true
    }

    fun consumeBack(): Boolean {
        if (!visible || dismissed) return false
        dismissed = true
        return true
    }
}
