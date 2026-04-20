/**
 *  Hubitat - FLEX35 - Curtain Child
 *
 *  Copyright 2025 VH/TRATO
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Version 1.1 - 20/04/2026
 *
 *  Cada cortina usa UM PAR de outputs:
 *    outputUp   → abre (sobe)
 *    outputDown → fecha (desce)
 *  INTERTRAVAMENTO: nunca os dois ligados ao mesmo tempo.
 *  TEMPO DE CURSO: motor desliga automaticamente após travelTime segundos.
 */
metadata {
    definition (
        name: "FLEX35 Curtain",
        namespace: "TRATO",
        author: "VH"
    ) {
        capability "WindowShade"    // open(), close(), stop() + attribute windowShade

        attribute "outputUp",   "number"
        attribute "outputDown", "number"
    }

    preferences {
        input name: "travelTime",   type: "number", title: "Tempo de curso completo (segundos)", defaultValue: 30, required: true
        input name: "debugLogging", type: "bool",   title: "Enable debug logging",               defaultValue: false
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    log.info "FLEX35 Curtain child instalado"
    initialize()
}

def updated() {
    log.info "FLEX35 Curtain child atualizado"
    initialize()
}

def initialize() {
    def up   = device.getDataValue("outputUp")?.toInteger()
    def down = device.getDataValue("outputDown")?.toInteger()
    if (up)   sendEvent(name: "outputUp",   value: up)
    if (down) sendEvent(name: "outputDown", value: down)
}

// ── Comandos principais ────────────────────────────────────────────────────────

def open() {
    if (debugLog()) log.debug "${device.displayName}: ABRIR (outputUp=${getOutputUp()}, travelTime=${getTravelTime()}s)"
    unschedule(motorDoneOpen)
    unschedule(motorDoneClosed)
    // Intertravamento: garante descer desligado antes de ligar subir
    parent.setOutput(getOutputDown(), 0)
    pauseExecution(200)
    parent.setOutput(getOutputUp(), 100)
    sendEvent(name: "windowShade", value: "opening")
    runIn(getTravelTime(), motorDoneOpen)
}

def close() {
    if (debugLog()) log.debug "${device.displayName}: FECHAR (outputDown=${getOutputDown()}, travelTime=${getTravelTime()}s)"
    unschedule(motorDoneOpen)
    unschedule(motorDoneClosed)
    // Intertravamento: garante subir desligado antes de ligar descer
    parent.setOutput(getOutputUp(), 0)
    pauseExecution(200)
    parent.setOutput(getOutputDown(), 100)
    sendEvent(name: "windowShade", value: "closing")
    runIn(getTravelTime(), motorDoneClosed)
}

def stopPositionChange() {
    if (debugLog()) log.debug "${device.displayName}: PARAR"
    unschedule(motorDoneOpen)
    unschedule(motorDoneClosed)
    parent.setOutput(getOutputUp(),   0)
    parent.setOutput(getOutputDown(), 0)
    sendEvent(name: "windowShade", value: "partially open")
}

def motorDoneOpen() {
    if (debugLog()) log.debug "${device.displayName}: curso de abertura concluído"
    parent.setOutput(getOutputUp(), 0)
    sendEvent(name: "windowShade", value: "open")
}

def motorDoneClosed() {
    if (debugLog()) log.debug "${device.displayName}: curso de fechamento concluído"
    parent.setOutput(getOutputDown(), 0)
    sendEvent(name: "windowShade", value: "closed")
}

def refresh() {
    parent.refresh()
}

// ── Feedback do parse do pai ───────────────────────────────────────────────────

/**
 * Chamado pelo pai quando recebe status de um output que pertence a esta cortina.
 * Só atualiza o estado se não houver curso agendado em andamento.
 */
def handleOutputFeedback(int outputNum, int level) {
    def up   = getOutputUp()
    def down = getOutputDown()
    if (outputNum == up && level > 0)   sendEvent(name: "windowShade", value: "opening")
    if (outputNum == down && level > 0) sendEvent(name: "windowShade", value: "closing")
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private int getOutputUp() {
    return device.getDataValue("outputUp")?.toInteger() ?: 0
}

private int getOutputDown() {
    return device.getDataValue("outputDown")?.toInteger() ?: 0
}

private int getTravelTime() {
    return (settings.travelTime ?: 30).toInteger()
}

private boolean debugLog() {
    return settings.debugLogging == true
}
