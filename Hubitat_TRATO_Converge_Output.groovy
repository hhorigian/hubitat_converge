/**
 *  Hubitat - Converge - Output Child 
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
        name: "FLEX35 Output",
        namespace: "TRATO",
        author: "VH"
    ) {
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"
        
        attribute "outputNumber", "number"
    }
}

def installed() {
    log.info "FLEX35 Output child device installed"
    initialize()
}

def updated() {
    log.info "FLEX35 Output child device updated"
    initialize()
}

def initialize() {
    // Extract output number from device network ID
    def outputNum = device.deviceNetworkId.split("-OUT")[1].toInteger()
    sendEvent(name: "outputNumber", value: outputNum)
}

def on() {
    if (settings.debugLogging) log.debug "Turning output ${device.currentValue("outputNumber")} on"
    parent.setOutput(device.currentValue("outputNumber"), 100)
}

def off() {
    if (settings.debugLogging) log.debug "Turning output ${device.currentValue("outputNumber")} off"
    parent.setOutput(device.currentValue("outputNumber"), 0)
}

def setLevel(level, rate = null) {
    if (settings.debugLogging) log.debug "Setting output ${device.currentValue("outputNumber")} to ${level}%"
    parent.setOutput(device.currentValue("outputNumber"), level)
}

def refresh() {
    if (settings.debugLogging) log.debug "Refreshing output ${device.currentValue("outputNumber")}"
    parent.refresh()
}