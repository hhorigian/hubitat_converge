/**
 *  Hubitat - MolSmart - Converge Controller
 *  
 *
 *  Copyright 2025 VH/TRATO
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *        
 *  Version 1.0 - 30/4/2025 - Beta 1.0
 *                 
 *                 
 *                 
 *                 
 *                
 */
metadata {
    definition (
        name: "FLEX35 Lighting Controller",
        namespace: "TRATO",
        author: "VH",
    ) {
        capability "Refresh"
        capability "Initialize"
        capability "Switch"
        
        // Main device doesn't need switch capabilities since we'll use child devices
        attribute "connection", "string"
        attribute "controllerStatus", "string"
        
        // Command to create all child devices at once
        command "createChildDevices"
        command "masterOn"
        command "masterOff"
        
        // 35 scene activation controls
        (1..35).each { num ->
            command "activateScene${num}"
            command "deactivateScene${num}"
        }
        
        // Attributes for scene feedback
        (1..35).each { num ->
            attribute "scene${num}", "string"
        }
    }

    preferences {
        input name: "ipAddress", type: "text", title: "IP Address", required: true
        input name: "port", type: "number", title: "Port", defaultValue: 4999, required: true
        input name: "reconnectDelay", type: "number", title: "Reconnect Delay (seconds)", defaultValue: 10, required: true
        input name: "autoCreateChildren", type: "bool", title: "Auto-create child devices", defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    log.warn "FLEX35 driver installed"
    initialize()
}

def on() {
    masterOn()
    
}

def off() {
    
    masterOff()
    
}

def updated() {
    log.warn "FLEX35 driver updated"
    initialize()
}

def initialize() {
    state.lastCommandSent = 0
    state.lastResponseReceived = 0
    state.reconnectAttempts = 0
    
    // Close any existing connection
    interfaces.rawSocket.close()
    
    // Open new connection
    connectToDevice()
    
    // Schedule periodic refresh
    runEvery5Minutes(refresh)
    
    // Create child devices if enabled
    if (settings.autoCreateChildren) {
        runIn(5, createChildDevices)
    }
}

def createChildDevices() {
    (1..35).each { outputNum ->
        try {
            def childDni = "${device.deviceNetworkId}-OUT${outputNum}"
            def child = getChildDevice(childDni)
            
            if (!child) {
                if (settings.debugLogging) log.debug "Creating child device for output ${outputNum}"
                child = addChildDevice(
                    "TRATO", 
                    "FLEX35 Output", 
                    childDni, 
                    [
                        name: "${device.displayName} Output ${outputNum}", 
                        label: "${device.displayName} Output ${outputNum}",
                        isComponent: false
                    ]
                )
                child.sendEvent(name: "switch", value: "off")
                child.sendEvent(name: "level", value: 0)
            }
        } catch (e) {
            log.error "Error creating child device for output ${outputNum}: ${e}"
        }
    }
}

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
        sendEvent(name: "connection", value: "connected")
        sendEvent(name: "controllerStatus", value: "online")
        refresh()
    } else {
        log.warn "Connection verification failed - reconnecting"
        sendEvent(name: "connection", value: "disconnected")
        sendEvent(name: "controllerStatus", value: "offline")
        connectToDevice()
    }
}

String encodeCommand(int Numero) {
    //log.debug "entrou no encode"
    if (Numero < 1 || Numero > 35) {
        throw new IllegalArgumentException("Numero must be between 1-35")
    }
    char encodedChar = (Numero + 64) as char
    //log.debug "resultado do encode = " + encodedChar
    return encodedChar
}


int decodeCharToNumero(String inputChar) {
    if (inputChar == null || inputChar.length() != 1) {
        throw new IllegalArgumentException("Input must be a single character")
    }

    int asciiValue = inputChar.charAt(0)
    
    // Supported ranges mapping to 1-35
    if (asciiValue >= 65 && asciiValue <= 90) {  // A-Z (1-26)
        return asciiValue - 64
    } 
    else if (asciiValue >= 97 && asciiValue <= 122) {  // a-z (also 1-26)
        return asciiValue - 96
    }
    else if (asciiValue >= 33 && asciiValue <= 47) {  ! to / (1-15)
        return asciiValue - 32
    }
    else if (asciiValue >= 58 && asciiValue <= 64) {  // : to @ (16-22)
        return asciiValue - 42
    }
    else if (asciiValue >= 91 && asciiValue <= 93) {  // [ to ] (27-29)
        return asciiValue - 64
    }
    else if (asciiValue >= 123 && asciiValue <= 125) { // { to } (mapped to 30-32)
        return asciiValue - 93
    }
    else if (asciiValue >= 94 && asciiValue <= 96) {   // ^ to ` (mapped to 33-35)
        return asciiValue - 61
    }
    else {
        throw new IllegalArgumentException("Unsupported character (must map to 1-35)")
    }
}



def parse(String msg) {
    if (settings.debugLogging) log.debug "Received: ${msg}"
    state.lastResponseReceived = now()

    def newmsg = hubitat.helper.HexUtils.hexStringToByteArray(msg)
    def message = new String(newmsg)
    state.lastmessage = message
    log.debug "****** New Block LOG Parse ********"
    log.debug "Last Msg: ${message}"
    

    // Process output status updates (1A-1i)
    def outputMatcher = message =~ /1([A-Za-z])(\d{2})/
    if (outputMatcher) {
        def outputChar = outputMatcher[0][1]
        def level = outputMatcher[0][2].toInteger()
        if (level == 10) level = 100
        def outputNum = decodeCharToNumero(outputChar)
        
        log.debug "Debuger en el parse = " + outputNum
        //outputNum = outputChar.toUpperCase().charAt(0) - 'A'.charAt(0) + 1       
        
        if (outputNum >= 1 && outputNum <= 35) {
            updateChildOutput(outputNum, level)
        }
        return
    }
    
    // Process scene status updates (1@)
    def sceneMatcher = message =~ /1@([A-Za-z]{2})/
    if (sceneMatcher) {
        def sceneCode = sceneMatcher[0][1]
        def sceneNum = sceneCode.charAt(0).toUpperCase() - 'A'.charAt(0) + 1
        def sceneStatus = sceneCode.charAt(1) == 'A' ? "activated" : "deactivated"
        
        if (sceneNum >= 1 && sceneNum <= 35) {
            sendEvent(name: "scene${sceneNum}", value: sceneStatus)
            if (settings.debugLogging) log.debug "Scene ${sceneNum} ${sceneStatus}"
        }
        return
    }
    
    log.warn "Unrecognized message: ${message}"
}

def updateChildOutput(outputNum, level) {
    def childDni = "${device.deviceNetworkId}-OUT${outputNum}"
    def child = getChildDevice(childDni)
    
    if (child) {
        def switchState = level > 0 ? "on" : "off"
        child.sendEvent(name: "switch", value: switchState)
        child.sendEvent(name: "level", value: level)
        if (settings.debugLogging) log.debug "Output ${outputNum} updated: ${switchState} (${level}%)"
    } else if (settings.autoCreateChildren) {
        log.warn "Child device for output ${outputNum} not found - will attempt to create"
        runIn(2, createChildDevices)
    }
}

def sendCommand(String cmd) {
    
    
    //Function to check for valid Complete Command Length
    def length = cmd.length()
    if (length % 2 != 0) {
        cmd = cmd + "0"
    }
        
    if (settings.debugLogging) log.debug "Sending command: ${cmd}"
    try {
        interfaces.rawSocket.sendMessage("${cmd}\r")
        state.lastCommandSent = now()
    } catch (e) {
        log.error "Failed to send command: ${e.message}"
        sendEvent(name: "connection", value: "disconnected")
        sendEvent(name: "controllerStatus", value: "offline")
        runIn(settings.reconnectDelay.toInteger(), connectToDevice)
    }
}




def setOutput(outputNum, level) {
    level = level as Integer
    if (level < 0) level = 0
    if (level > 100) level = 100
    def10 = 10
    def0 = "0"
    outputNum = outputNum as Integer
    def charCode = encodeCommand(outputNum)
    //def charCode = (char)(64 + outputNum) // Correct conversion to character
    log.debug "outputNum es = " + outputNum
    log.debug "charCode es igual = " + charCode
    def cmd
    
    if (level < 10) {
        cmd = "1${charCode}"+"00"
        log.debug "level = " + level
    } 
    if (level == 100) {
        cmd = "1${charCode}${def10}"
        log.debug "level = " + level
    } 
    else {
        cmd = "1${charCode}${level}"
        log.debug "level = " + level
    }
    
    sendCommand(cmd)
    updateChildOutput(outputNum, level)
}


// Master control functions
def masterOn() {
    if (settings.debugLogging) log.debug "Turning all outputs on"
    (1..26).each { outputNum ->
        setOutput(outputNum, 100) // Turn on at 100% level
    }
}

def masterOff() {
    if (settings.debugLogging) log.debug "Turning all outputs off"
    (1..26).each { outputNum ->
        setOutput(outputNum, 0) // Turn off (0% level)
    }
}


// Corrected Scene Control Methods - use direct method definitions instead of string interpolation
def activateScene1() { sendCommand("1@AA") }
def deactivateScene1() { sendCommand("1@AD") }
def activateScene2() { sendCommand("1@BA") }
def deactivateScene2() { sendCommand("1@BD") }
def activateScene3() { sendCommand("1@CA") }
def deactivateScene3() { sendCommand("1@CD") }
def activateScene4() { sendCommand("1@DA") }
def deactivateScene4() { sendCommand("1@DD") }
def activateScene5() { sendCommand("1@EA") }
def deactivateScene5() { sendCommand("1@ED") }
def activateScene6() { sendCommand("1@FA") }
def deactivateScene6() { sendCommand("1@FD") }
def activateScene7() { sendCommand("1@GA") }
def deactivateScene7() { sendCommand("1@GD") }
def activateScene8() { sendCommand("1@HA") }
def deactivateScene8() { sendCommand("1@HD") }
def activateScene9() { sendCommand("1@IA") }
def deactivateScene9() { sendCommand("1@ID") }
def activateScene10() { sendCommand("1@JA") }
def deactivateScene10() { sendCommand("1@JD") }
def activateScene11() { sendCommand("1@KA") }
def deactivateScene11() { sendCommand("1@KD") }
def activateScene12() { sendCommand("1@LA") }
def deactivateScene12() { sendCommand("1@LD") }
def activateScene13() { sendCommand("1@MA") }
def deactivateScene13() { sendCommand("1@MD") }
def activateScene14() { sendCommand("1@NA") }
def deactivateScene14() { sendCommand("1@ND") }
def activateScene15() { sendCommand("1@OA") }
def deactivateScene15() { sendCommand("1@OD") }
def activateScene16() { sendCommand("1@PA") }
def deactivateScene16() { sendCommand("1@PD") }
def activateScene17() { sendCommand("1@QA") }
def deactivateScene17() { sendCommand("1@QD") }
def activateScene18() { sendCommand("1@RA") }
def deactivateScene18() { sendCommand("1@RD") }
def activateScene19() { sendCommand("1@SA") }
def deactivateScene19() { sendCommand("1@SD") }
def activateScene20() { sendCommand("1@TA") }
def deactivateScene20() { sendCommand("1@TD") }
def activateScene21() { sendCommand("1@UA") }
def deactivateScene21() { sendCommand("1@UD") }
def activateScene22() { sendCommand("1@VA") }
def deactivateScene22() { sendCommand("1@VD") }
def activateScene23() { sendCommand("1@WA") }
def deactivateScene23() { sendCommand("1@WD") }
def activateScene24() { sendCommand("1@XA") }
def deactivateScene24() { sendCommand("1@XD") }
def activateScene25() { sendCommand("1@YA") }
def deactivateScene25() { sendCommand("1@YD") }
def activateScene26() { sendCommand("1@ZA") }
def deactivateScene26() { sendCommand("1@ZD") }
def activateScene27() { sendCommand("1@aA") }
def deactivateScene27() { sendCommand("1@aD") }
def activateScene28() { sendCommand("1@bA") }
def deactivateScene28() { sendCommand("1@bD") }
def activateScene29() { sendCommand("1@cA") }
def deactivateScene29() { sendCommand("1@cD") }
def activateScene30() { sendCommand("1@dA") }
def deactivateScene30() { sendCommand("1@dD") }
def activateScene31() { sendCommand("1@eA") }
def deactivateScene31() { sendCommand("1@eD") }
def activateScene32() { sendCommand("1@fA") }
def deactivateScene32() { sendCommand("1@fD") }
def activateScene33() { sendCommand("1@gA") }
def deactivateScene33() { sendCommand("1@gD") }
def activateScene34() { sendCommand("1@hA") }
def deactivateScene34() { sendCommand("1@hD") }
def activateScene35() { sendCommand("1@iA") }
def deactivateScene35() { sendCommand("1@iD") }

def refresh() {
    // Request status for all outputs
    (1..35).each { num ->
    	def charCode = (char)(64 + num) 
        //sendCommand("1${charCode}??")
    }
    
    // Request scene status
    sendCommand("1@??")
}

def uninstalled() {
    // Clean up child devices
    getChildDevices().each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
    interfaces.rawSocket.close()
}

def socketStatus(String status) {
    log.warn "Socket status: ${status}"
    if (status == "disconnected") {
        sendEvent(name: "connection", value: "disconnected")
        sendEvent(name: "controllerStatus", value: "offline")
        runIn(settings.reconnectDelay.toInteger(), connectToDevice)
    }
}