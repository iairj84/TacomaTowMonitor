package com.tacomatow.monitor.obd

/**
 * Process-wide singleton so the phone UI and the Android Auto
 * CarAppService read the same polling loop / ELM socket.
 */
object ObdHolder {
    val repository = ObdRepository()
}
