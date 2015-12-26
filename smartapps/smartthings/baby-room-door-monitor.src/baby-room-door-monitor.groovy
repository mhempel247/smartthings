/**
 *  Copyright 2015 SmartThings
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
 *  Garage Door Monitor
 *
 *  Author: SmartThings
 */
definition(
    name: "Baby Room Door Monitor",
    namespace: "smartthings",
    author: "SmartThings",
    description: "Monitor your baby's door and use it to control a Philips Hue light",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Meta/garage_contact.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Meta/garage_contact@2x.png"
)

preferences {
	section("Use this baby room door multi-sensor:") {
		input "multisensor", "capability.accelerationSensor", title: "Which multi-sensor?", required:true
	}
	section("To control these bulbs") {
		input "hues", "capability.colorControl", title: "Which Hue bulbs?", required:true, multiple:true
	}
	section("And use these light effects")
	{
		input "color", "enum", title: "Hue Color?", required: false, multiple:false, options: ["White","Daylight","Soft White","Warm White","Red","Green","Blue","Yellow","Orange","Purple","Pink"]
		input "lightLevel", "enum", title: "Light Level?", required: false, options: [[10:"10%"],[20:"20%"],[30:"30%"],[40:"40%"],[50:"50%"],[60:"60%"],[70:"70%"],[80:"80%"],[90:"90%"],[100:"100%"]]
	}
}

def installed()
{
	initialize()
}

def updated()
{
	unsubscribe()
	initialize()
}

def initialize() 
{
	state.mask_contact		=0x00000001
    state.mask_accel		=0x00000002
    state.mask_lights		=0x00000004
    state.mask_lights_fade	=0x00000008
    state.mask_sunlight		=0x00000010
 	
    state.force_night=0
    //now read the status of the devices we need and normalize the lights
    //turnLightsOff()
	translateColor(color,lightLevel)
	determineState()
    realizeState()
    
    //then set up the event handling to respond to changes
	subscribe(multisensor, "contact", contactHandler)
	subscribe(multisensor, "acceleration", accelerationHandler)	
    
    subscribe(location, "position", locationPositionChange)
	subscribe(location, "sunriseTime", sunriseHandler)
	subscribe(location, "sunsetTime", sunsetHandler)
}

def areLightsOn() {
	def some_on=0
    def some_off=0
    hues.each { hue ->
    	if (hue.currentSwitch=="on") {some_on=1} else if (hue.currentSwitch=="off") {some_off=1}
    }
    if (some_on==1)
    {
    	log.debug "some hues are on"
    }
    else log.debug "hues are off"
   	some_on
}
def determineState() {
	log.debug "determineState()"
	state.state_current=0
    state.state_changed=0
    
    def s_contact=multisensor.currentContact
    def s_acc=multisensor.currentAcceleration
    
	if (s_contact=="open" || s_contact=="garage-open")			state.state_current=state.state_current | state.mask_contact
    else if (s_contact=="closed" || s_contact=="garage-closed") state.state_current=state.state_current & (0xffffffff^state.mask_contact)
    log.debug "s_contact is $s_contact"
    
    if (s_acc == "active")										state.state_current=state.state_current | state.mask_accel
    else if (s_acc=="inactive")									state.state_current=state.state_current & (0xffffffff^state.mask_accel)
    log.debug "s_acc is $s_acc"
    
    def some_on=areLightsOn()
    if (some_on==1)
    {
    	hues.each { hue -> hue.off() }
    }
    
    def s = getSunriseAndSunset(zipCode: zipCode, sunriseOffset: sunriseOffset, sunsetOffset: sunsetOffset)
	def curr=now()
    state.riseTime = s.sunrise.time
	state.setTime = s.sunset.time
    if (state.force_night!=0)
    {
    	state.state_current=state.state_current & (0xffffffff^state.mask_sunlight)
        log.debug "sun is Down! (forced for debugging)"
 	}
	else if (curr>=state.riseTime && curr<state.setTime)				
    {
    	state.state_current=state.state_current | state.mask_sunlight
    	log.debug "sun is Up!"
    }
    else														
    {
    	state.state_current=state.state_current & (0xffffffff^state.mask_sunlight)
        log.debug "sun is Down!"
 	}   
}
def realizeState() {
	log.debug "realizeState()"
    
	if ((state.state_current & state.mask_contact)==0)
    {
    	log.debug "contact sensor is closed"
    	//door is closed. lights should be off
        if ((state.state_current & state.mask_lights)!=0)
        {
        	log.debug "lights are on, turning off!"
    		turnLightsOff()
        }
        return
    }
    if ((state.state_current & state.mask_sunlight)!=0)
    {
    	log.debug "sun is up"
    	//sun is up. lights should be off
        if ((state.state_current & state.mask_lights)!=0)
        {
        	log.debug "lights are on, turning off!"
    		turnLightsOff()
        }
        return
    }
    //we get here, there is a chance we need to turn the light on
    if ((state.state_current & state.mask_accel)!=0)
    {
    	log.debug "accelerometer is active, turning lights on"
    	turnLightsOn(state.translatedColor.hue, state.translatedColor.saturation, state.translatedColor.level,1)
    }
}
def realizeStateChange() {
	log.debug "realizeStateChange()"
    log.debug "state change value is ${state.state_changed}"
    //if the door is now closed, we turn the lights off if they are on
	if ((state.state_changed & state.mask_contact)!=0)//the contact sensor state changed
    {
    	log.debug "contact sensor state change"
    	if ((state.state_current & state.mask_contact)==0)
        {
    		log.debug "contact sensor is closed"
    		//door is closed. lights should be off
            if (areLightsOn()!=0)
            {
                log.debug "lights are on, turning off!"
                turnLightsOff()
                //we also ignore the accelerometer at this point
                 state.state_changed=state.state_changed & (0xffffffff^state.mask_accel)
            }
        }
        else
        {
        	//door is open...anything here depends on the accelerometer
        	log.debug "contact sensor is open...do nothing"
    	}
        //clear the change flag
        state.state_changed=state.state_changed & (0xffffffff^state.mask_contact)
    }
    //if the sun is now up, we turn the lights off if they are on
    if ((state.state_changed & state.mask_sunlight)!=0)//the sunset/sunrise state changed
    {
    	log.debug "sunrise/sunset state change"
    	if ((state.state_current & state.mask_sunlight)!=0)
        {
    		log.debug "sun is now Up!"
    		//sun is up. lights should be off
            if (areLightsOn()!=0)
            {
                log.debug "lights are on, turning off!"
                turnLightsOff()
                //we also ignore the accelerometer at this point
                state.state_changed=state.state_changed & (0xffffffff^state.mask_accel)
            }
        }
        else
        {
        	//sun is down...anything here depends on the accelerometer
        	log.debug "sun is down...do nothing"
    	}
        //clear the change flag
        state.state_changed=state.state_changed & (0xffffffff^state.mask_sunlight)
    }
    //if the acceleration is active (fast door open or close), we turn the lights on if they are off
    if ((state.state_changed & state.mask_accel)!=0)//the accelerometer sensor state changed
    {
    	log.debug "acceleration state change"
    	if ((state.state_current & state.mask_accel)!=0 && (state.state_current & state.mask_sunlight)==0)
        {
    		log.debug "acceleration sensor is active, sun is down...lights should turn on"
    		if (areLightsOn()==0)
            {
                log.debug "lights are off, turning on!"
                turnLightsOn(state.translatedColor.hue, state.translatedColor.saturation, state.translatedColor.level,0)
            }
        }
        else
        {
        	//acceleration is inactive or sun is up...nothing to do
            log.debug "acceleration is inactive or sun is up...do nothing"
        }
        //clear the change flag
        state.state_changed=state.state_changed & (0xffffffff^state.mask_accel)
    }
    //if the lights' state changed
    if ((state.state_changed & state.mask_lights)!=0)//the lights' state changed
    {
    	//clear the change flag
        state.state_changed=state.state_changed & (0xffffffff^state.mask_lights)
    }
    //if the lights' fade state changed
    if ((state.state_changed & state.mask_lights_fade)!=0)//the lights' fade state changed
    {
    	//clear the change flag
        state.state_changed=state.state_changed & (0xffffffff^state.mask_lights_fade)
    }
}

private translateColor(color, level) {
	def hueColor = 0
	def saturation = 100
	log.debug "translateColor()"
    
	switch(color) {
		case "White":
			hueColor = 52
			saturation = 19
			break;
		case "Daylight":
			hueColor = 53
			saturation = 91
			break;
		case "Soft White":
			hueColor = 23
			saturation = 56
			break;
		case "Warm White":
			hueColor = 20
			saturation = 80 //83
			break;
		case "Red":
			hueColor = 100
			break;
		case "Green":
			hueColor = 39
			break;
		case "Blue":
			hueColor = 70
			break;
		case "Yellow":
			hueColor = 25
			break;
		case "Orange":
			hueColor = 10
			break;
		case "Purple":
			hueColor = 75
			break;
		case "Pink":
			hueColor = 83
			break;
	}

	def newValue = [hue: hueColor, saturation: saturation, level: level as Integer ?: 100]
	log.debug "newColor is $newValue"
    state.translatedColor=newValue
}
private turnLightsOn(hue,saturation,maxlevel,immediate) {
	state.lights_hue=hue
    state.lights_saturation=saturation
    state.lights_maxlevel=maxlevel
    log.debug "turnLightsOn()"
    
    //override animation of lights
    if (immediate!=0)
    {
    	state.lights_currlevel=maxlevel
    	def newValue = [hue: state.lights_hue, saturation: state.lights_saturation, level: state.lights_currlevel]
		hues*.setColor(newValue)
    	hues*.on()
    }
    else {
    	//take this out later and replace with animations
        immediate=1
    	state.lights_currlevel=maxlevel
    	def newValue = [hue: state.lights_hue, saturation: state.lights_saturation, level: state.lights_currlevel]
		
        hues*.setColor(newValue)
    	hues*.on()
    }
    
    def prevState=state.state_current
	state.state_current=state.state_current | state.mask_lights
	state.state_current=state.state_current | state.mask_lights_fade
	
    if ((state.state_current & state.mask_lights) != (prevState | state.mask_lights))
    {
    	log.debug "lights state change"
    	state.state_changed=state.state_changed | state.mask_lights
  	}
    else
	{
    	log.debug "NO lights state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_lights)
    }
    
    if ((state.state_current & state.mask_lights_fade) != (prevState | state.mask_lights_fade))
    {
    	log.debug "lights-fade state change"
    	state.state_changed=state.state_changed | state.mask_lights_fade
  	}
    else
	{
    	log.debug "NO lights-fade state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_lights_fade)
    }

	realizeStateChange()
}
private turnLightsOff() {
	log.debug "turnLightsOff()"
	hues*.off()
    state.lights_currlevel=0
    
	def prevState=state.state_current
	state.state_current=state.state_current & (0xffffffff^state.mask_lights)
	state.state_current=state.state_current & (0xffffffff^state.mask_lights_fade)
	
    if ((state.state_current & state.mask_lights) != (prevState | state.mask_lights))
    {
    	log.debug "lights state change"
    	state.state_changed=state.state_changed | state.mask_lights
  	}
    else
	{
    	log.debug "NO lights state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_lights)
    }
    
    if ((state.state_current & state.mask_lights_fade) != (prevState | state.mask_lights_fade))
    {
    	log.debug "lights-fade state change"
    	state.state_changed=state.state_changed | state.mask_lights_fade
  	}
    else
	{
    	log.debug "NO lights-fade state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_lights_fade)
    }

	realizeStateChange()
}

def locationPositionChange(evt) {
	log.trace "locationChange()"
    
	def s = getSunriseAndSunset(zipCode: zipCode, sunriseOffset: sunriseOffset, sunsetOffset: sunsetOffset)
	def curr=now()
    state.riseTime = s.sunrise.time
	state.setTime = s.sunset.time
    def prevState=state.state_current
	if (state.force_night!=0)									state.state_current=state.state_current & (0xffffffff^state.mask_sunlight)
    else if (curr>=state.riseTime && curr<state.setTime)		state.state_current=state.state_current | state.mask_sunlight
    else														state.state_current=state.state_current & (0xffffffff^state.mask_sunlight)
    
    if ((state.state_current & state.mask_sunlight) != (prevState | state.mask_sunlight))
    {
    	log.debug "sunrise/sunset state change"
    	state.state_changed=state.state_changed | state.mask_sunlight
  	}
    else
	{
    	log.debug "NO sunrise/sunset state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_sunlight)
    }
    realizeStateChange()
}
def sunriseHandler(evt) {
	log.trace "sunriseHandler()"
    
    if (state.force_night!=0) 
    {
    	log.debug"sunrise occured but we are forcing night for debugging...state remains at night"
    	return
    }
    def prevState=state.state_current
	state.state_current=state.state_current | state.mask_sunlight
    if ((state.state_current & state.mask_sunlight) != (prevState | state.mask_sunlight)) 
    {
    	log.debug "sunrise/sunset state change"
    	state.state_changed=state.state_changed | state.mask_sunlight
  	} 
    else
    {
		log.debug "NO sunrise/sunset state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_sunlight)
	}
    realizeStateChange()
}
def sunsetHandler(evt) {
	log.trace "sunsetHandler()"
    
    def prevState=state.state_current
	state.state_current=state.state_current & (0xffffffff^state.mask_sunlight)
    if ((state.state_current & state.mask_sunlight) != (prevState | state.mask_sunlight)) 
    {
    	log.debug "sunrise/sunset state change"
    	state.state_changed=state.state_changed | state.mask_sunlight
  	}
    else
	{
    	log.debug "NO sunrise/sunset state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_sunlight)
    }
    realizeStateChange()
}

def contactHandler(evt) {
	log.debug "contactHandler()"
    
	def prevState=state.state_current
	if (evt.value == "open") {
		log.debug "contact sensor is open!"
        state.state_current=state.state_current | state.mask_contact
	} 
    else if (evt.value == "closed") {
		log.debug "contact sensor is closed!"
		state.state_current=state.state_current & (0xffffffff^state.mask_contact)
    }
    if ((state.state_current & state.mask_contact) != (prevState | state.mask_contact)) 
    {
    	log.debug "contact sensor state change"
    	state.state_changed=state.state_changed | state.mask_contact
  	}
    else
	{
    	log.debug "NO contact sensor state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_contact)
    }
    realizeStateChange()
}
def accelerationHandler(evt) {
	log.debug "accelerationHandler()"
    
	def prevState=state.state_current
	if (evt.value == "active") {
		log.debug "acceleration sensor is active!"
	    state.state_current=state.state_current | state.mask_accel
	} 
    else if (evt.value == "inactive") {
		log.debug "acceleration sensor is inactive!"
		state.state_current=state.state_current & (0xffffffff^state.mask_accel)
	}
    
    if ((state.state_current & state.mask_accel) != (prevState | state.mask_accel)) 
    {
    	log.debug "acceleration state change"
    	state.state_changed=state.state_changed | state.mask_accel
  	}
    else
	{
    	log.debug "NO acceleration state change"
    	state.state_changed=state.state_changed & (0xffffffff^state.mask_accel)
    }
    realizeStateChange()
	
    /*def latestThreeAxisState = multisensor.threeAxisState // e.g.: 0,0,-1000
	if (latestThreeAxisState) {
		def isOpen = Math.abs(latestThreeAxisState.xyzValue.z) > 250 // TODO: Test that this value works in most cases...
		def isNotScheduled = state.status != "scheduled"

		if (!isOpen) {
			clearSmsHistory()
			clearStatus()
		}

		if (isOpen && isNotScheduled) {
			runIn(maxOpenTime * 60, takeAction, [overwrite: false])
			state.status = "scheduled"
		}

	}
	else {
		log.warn "COULD NOT FIND LATEST 3-AXIS STATE FOR: ${multisensor}"
	}*/
}

