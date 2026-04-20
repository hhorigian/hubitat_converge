/**
 *  Hubitat - FLEX35 - Curtain Controller (Parent)
 *
 *  Copyright 2025 VH/TRATO
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Version 1.0 - 20/04/2026 - Beta 1.0
 *
 *  Cada cortina usa UM PAR de outputs consecutivos:
 *    outputUp   (par) → sobe/abre
 *    outputDown (ímpar seguinte) → desce/fecha
 *  INTERTRAVAMENTO: nunca os dois outputs de um par ligados ao mesmo tempo.
 */
metadata {
    definition (
        name: "FLEX35 Curtain Controller",
        namespace: "TRATO",
        author: "VH"
    ) {
        capability "Refresh"
        capability "Initialize"

        attribute "connection",       "string"
        attribute "controllerStatus", "string"

        command "createCurtainChildren"
    }

    preferences {
        input name: "ipAddress",          type: "text",   title: "IP Address",                                                        required: true
        input name: "port",               type: "number", title: "Port",                                                               defaultValue: 4999, required: true
        input name: "reconnectDelay",     type: "number", title: "Reconnect Delay (seconds)",                                          defaultValue: 10,   required: true
        input name: "numCurtains",        type: "number", title: "Quantas cortinas criar?",                                            defaultValue: 1,    required: true
        input name: "startOutput",        type: "number", title: "Começar a partir de qual output? (este=subir, próximo=descer)",      defaultValue: 1,    required: true
        input name: "autoCreateChildren", type: "bool",   title: "Auto-criar child devices ao salvar",                                 defaultValue: true
        input name: "debugLogging",       type: "bool",   title: "Enable debug logging",                                              defaultValue: false
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────────

def installed() {
    log.warn "FLEX35 Curtain Controller installed"
    initialize()
}

def updated() {
    log.warn "FLEX35 Curtain Controller updated"
    initialize()
}

def initialize() {
    state.lastCommandSent     = 0
    state.lastResponseReceived = 0
    state.reconnectAttempts    = 0

    interfaces.rawSocket.close()
    connectToDevice()
    runEvery5Minutes(refresh)

    if (settings.autoCreateChildren) {
        runIn(5, createCurtainChildren)
    }
}

// ── Child devices ──────────────────────────────────────────────────────────────

def createCurtainChildren() {
    def numCurtains = (settings.numCurtains ?: 1).toInteger()
    def startOutput = (settings.startOutput ?: 1).toInteger()

    def lastOutputNeeded = startOutput + (numCurtains * 2) - 1
    if (lastOutputNeeded > 35) {
        log.error "Configuração inválida: ${numCurtains} cortinas a partir do output ${startOutput} precisaria do output ${lastOutputNeeded}, mas o máximo é 35."
        return
    }

    numCurtains.times { i ->
        def curtainIndex = i + 1
        def outputUp     = startOutput + (i * 2)
        def outputDown   = outputUp + 1
        def childDni     = "${device.deviceNetworkId}-CUR${curtainIndex}"

        try {
            def child = getChildDevice(childDni)
            if (!child) {
                if (settings.debugLogging) log.debug "Criando cortina ${curtainIndex}: outputUp=${outputUp}, outputDown=${outputDown}"
                child = addChildDevice(
                    "TRATO",
                    "FLEX35 Curtain",
                    childDni,
                    [
                        name:        "${device.displayName} Curtain ${curtainIndex}",
                        label:       "${device.displayName} Curtain ${curtainIndex}",
                        isComponent: false
                    ]
                )
                child.updateDataValue("outputUp",   outputUp.toString())
                child.updateDataValue("outputDown", outputDown.toString())
                child.sendEvent(name: "windowShade", value: "closed")
            } else {
                // Atualiza pares de output caso a configuração tenha mudado
                child.updateDataValue("outputUp",   outputUp.toString())
                child.updateDataValue("outputDown", outputDown.toString())
                if (settings.debugLogging) log.debug "Cortina ${curtainIndex} já existe – outputs atualizados (up=${outputUp}, down=${outputDown})"
            }
        } catch (e) {
            log.error "Erro ao criar/atualizar cortina ${curtainIndex}: ${e}"
        }
    }
}

// ── Comunicação TCP ────────────────────────────────────────────────────────────

def connectToDevice() {
    try {
        interfaces.rawSocket.connect("${settings.ipAddress}", settings.port.toInteger())
        runIn(5, verifyConnection)
        log.info "Attempting connection to FLEX35 at ${settings.ipAddress}:${settings.port}"
    } catch (e) {
        log.error "Connection failed: ${e.message}"
        runIn(settings.reconnectDelay.toInteger(), connectToDevice)
    }
}

def verifyConnection() {
    if (state.lastResponseReceived > state.lastCommandSent) {
        log.info "Connection verified"
        sendEvent(name: "connection",       value: "connected")
        sendEvent(name: "controllerStatus", value: "online")
        refresh()
    } else {
        log.warn "Connection verification failed - reconnecting"
        sendEvent(name: "connection",       value: "disconnected")
        sendEvent(name: "controllerStatus", value: "offline")
        connectToDevice()
    }
}

def socketStatus(String status) {
    log.warn "Socket status: ${status}"
    if (status == "disconnected") {
        sendEvent(name: "connection",       value: "disconnected")
        sendEvent(name: "controllerStatus", value: "offline")
        runIn(settings.reconnectDelay.toInteger(), connectToDevice)
    }
}

def sendCommand(String cmd) {
    if (cmd.length() % 2 != 0) cmd = cmd + "0"
    if (settings.debugLogging) log.debug "Sending command: ${cmd}"
    try {
        interfaces.rawSocket.sendMessage("${cmd}\r")
        state.lastCommandSent = now()
    } catch (e) {
        log.error "Failed to send command: ${e.message}"
        sendEvent(name: "connection",       value: "disconnected")
        sendEvent(name: "controllerStatus", value: "offline")
        runIn(settings.reconnectDelay.toInteger(), connectToDevice)
    }
}

// ── Encode / Decode ────────────────────────────────────────────────────────────

String encodeCommand(int numero) {
    if (numero < 1 || numero > 35) {
        throw new IllegalArgumentException("Numero must be between 1-35")
    }
    char encodedChar = (numero + 64) as char
    return encodedChar
}

int decodeCharToNumero(String inputChar) {
    if (inputChar == null || inputChar.length() != 1) {
        throw new IllegalArgumentException("Input must be a single character")
    }
    int ascii = inputChar.charAt(0)
    if (ascii >= 65  && ascii <= 90)  return ascii - 64
    if (ascii >= 97  && ascii <= 122) return ascii - 96
    if (ascii >= 33  && ascii <= 47)  return ascii - 32
    if (ascii >= 58  && ascii <= 64)  return ascii - 42
    if (ascii >= 91  && ascii <= 93)  return ascii - 64
    if (ascii >= 123 && ascii <= 125) return ascii - 93
    if (ascii >= 94  && ascii <= 96)  return ascii - 61
    throw new IllegalArgumentException("Unsupported character (must map to 1-35)")
}

// ── Controle de outputs (chamado pelos childs) ─────────────────────────────────

/**
 * Liga/desliga um output do FLEX35.
 * level 100 = ligado, 0 = desligado
 */
def setOutput(int outputNum, int level) {
    if (level < 0)   level = 0
    if (level > 100) level = 100
    def charCode = encodeCommand(outputNum)
    def cmd
    if (level == 0)        cmd = "1${charCode}00"
    else if (level == 100) cmd = "1${charCode}10"
    else                   cmd = "1${charCode}${String.format('%02d', level)}"
    if (settings.debugLogging) log.debug "setOutput: output=${outputNum}, level=${level}, cmd=${cmd}"
    sendCommand(cmd)
}

// ── Parse ──────────────────────────────────────────────────────────────────────

def parse(String msg) {
    if (settings.debugLogging) log.debug "Received: ${msg}"
    state.lastResponseReceived = now()

    def newmsg   = hubitat.helper.HexUtils.hexStringToByteArray(msg)
    def message  = new String(newmsg)
    state.lastmessage = message
    if (settings.debugLogging) log.debug "Parse message: ${message}"

    def outputMatcher = message =~ /1([A-Za-z])(\d{2})/
    if (outputMatcher) {
        def outputChar = outputMatcher[0][1]
        def level      = outputMatcher[0][2].toInteger()
        if (level == 10) level = 100
        def outputNum  = decodeCharToNumero(outputChar)
        if (settings.debugLogging) log.debug "Output ${outputNum} = ${level}%"
        notifyCurtainChildren(outputNum, level)
        return
    }

    if (settings.debugLogging) log.debug "Unrecognized message: ${message}"
}

/**
 * Encontra o child que usa esse output e atualiza seu estado.
 */
def notifyCurtainChildren(int outputNum, int level) {
    getChildDevices().each { child ->
        def up   = child.getDataValue("outputUp")?.toInteger()
        def down = child.getDataValue("outputDown")?.toInteger()
        if (up == null || down == null) return
        if (outputNum == up || outputNum == down) {
            child.handleOutputFeedback(outputNum, level)
        }
    }
}

def refresh() {
    sendCommand("1@??")
}

def uninstalled() {
    getChildDevices().each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
    interfaces.rawSocket.close()
}
