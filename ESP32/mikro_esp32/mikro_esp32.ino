#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <HardwareSerial.h>
#include <TinyGPSPlus.h>

#define GSM_RX_PIN 16
#define GSM_TX_PIN 17
#define GPS_RX_PIN 18
#define BUZZER_PIN 27

#define BLE_LED_PIN 33
#define WIFI_LED_PIN 25
#define GSM_LED_PIN 26

#define BLE_SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define BLE_CHAR_UUID    "abcd1234-ab12-cd34-ef00-1234567890ab"

const char* AP_SSID = "ESP_TRACKER";
const char* AP_PASS = "12345678";
#define UDP_PORT 5000

HardwareSerial gsmSerial(2);
HardwareSerial gpsSerial(1);
TinyGPSPlus gps;

bool alarmEnabled = true;
bool trackingEnabled = false;
bool buzzerActive = false;
bool alarmTriggered = false;
bool alarmSilenced = false;
bool distanceCalibrating = false;
unsigned long buzzerStartTime = 0;
unsigned long lastBuzzerBeep = 0;
unsigned long silenceTime = 0;
const unsigned long BUZZER_DURATION = 30000;
const unsigned long BEEP_INTERVAL = 1000;
const unsigned long SILENCE_DURATION = 300000;

bool rssiTestMode = false;
int currentRSSI = 0;
int rssiThreshold = -1000;
unsigned long lastRssiUpdate = 0;
const unsigned long RSSI_TIMEOUT = 10000;

unsigned long lastBLEAlertTime = 0;
unsigned long lastWiFiAlertTime = 0;
unsigned long lastSMSAlertTime = 0;
const unsigned long BLE_ALERT_COOLDOWN = 5000;
const unsigned long WIFI_ALERT_COOLDOWN = 10000;
const unsigned long SMS_ALERT_COOLDOWN = 60000;

enum TrackerState { 
    INIT, 
    SCAN_BLE,
    CHECK_DISTANCE,
    ALERT_SEND_BLE,
    SCAN_WIFI,
    WIFI_LISTEN,
    SCAN_GSM,
    ALERT_SEND_GSM,
    ALL_DOWN,
    RECONNECT_SCAN,
    SLEEP_MODE,
    ALARM_ACTIVE
};

TrackerState currentState = INIT;

BLEServer *pServer;
BLECharacteristic *pAlertChar;
bool bleConnected = false;
bool deviceConnected = false;

WiFiUDP udp;

double lastLat = 0.0;
double lastLon = 0.0;
bool gpsFixed = false;
unsigned long lastGpsUpdate = 0;

unsigned long stateStart = 0;
unsigned long lastStatusPrint = 0;
unsigned long lastGpsRead = 0;

const String alertPhoneNumber = "+905447661357";

const unsigned long STATE_TIMEOUT = 30000;
const unsigned long SLEEP_DURATION = 30000;
const unsigned long GPS_READ_INTERVAL = 20000;
const unsigned long STATUS_PRINT_INTERVAL = 300000;

void silenceAlarmPermanent() {
    buzzerActive = false;
    alarmTriggered = false;
    alarmSilenced = true;
    silenceTime = millis();
    digitalWrite(BUZZER_PIN, LOW);
    Serial.println("ALARM PERMANENTLY SILENCED - 5 minutes");
}

bool isAlarmSilenced() {
    if (!alarmSilenced) return false;
    
    if (millis() - silenceTime > SILENCE_DURATION) {
        alarmSilenced = false;
        Serial.println("Alarm silence period ended - monitoring resumed");
        return false;
    }
    return true;
}

void startBuzzer() {
    if (!alarmEnabled || isAlarmSilenced() || distanceCalibrating) {
        Serial.println("Buzzer blocked - alarm disabled/silenced/calibrating");
        return;
    }
    
    buzzerActive = true;
    alarmTriggered = true;
    buzzerStartTime = millis();
    lastBuzzerBeep = millis();
    
    Serial.println("BUZZER STARTED - Alarm active for 30 seconds");
    digitalWrite(BUZZER_PIN, HIGH);
    delay(500);
    digitalWrite(BUZZER_PIN, LOW);
}

void stopBuzzer() {
    if (!buzzerActive && !alarmTriggered) return;
    
    buzzerActive = false;
    alarmTriggered = false;
    digitalWrite(BUZZER_PIN, LOW);
    Serial.println("BUZZER STOPPED - Alarm silenced");
}

void handleBuzzer() {
    if (!buzzerActive || !alarmEnabled || isAlarmSilenced()) return;
    
    unsigned long currentTime = millis();
    
    if (currentTime - buzzerStartTime > BUZZER_DURATION) {
        buzzerActive = false;
        alarmTriggered = false;
        digitalWrite(BUZZER_PIN, LOW);
        Serial.println("BUZZER AUTO-STOPPED after 30 seconds");
        return;
    }
    
    if (currentTime - lastBuzzerBeep > BEEP_INTERVAL) {
        digitalWrite(BUZZER_PIN, HIGH);
        delay(200);
        digitalWrite(BUZZER_PIN, LOW);
        lastBuzzerBeep = currentTime;
    }
}

void updateLEDStatus() {
    digitalWrite(BLE_LED_PIN, bleConnected ? HIGH : LOW);
    
    bool wifiActive = (WiFi.softAPgetStationNum() > 0);
    digitalWrite(WIFI_LED_PIN, wifiActive ? HIGH : LOW);
    
    static unsigned long lastGSMCheck = 0;
    static bool gsmStatus = false;
    
    if (millis() - lastGSMCheck > 30000) {
        gsmStatus = checkGSMStatus();
        lastGSMCheck = millis();
    }
    
    digitalWrite(GSM_LED_PIN, gsmStatus ? HIGH : LOW);
}

bool checkDistanceAlert() {
    // Don't check alerts if not connected, calibrating, or tracking disabled
    if (!bleConnected || !deviceConnected || distanceCalibrating || !trackingEnabled) {
        return false;
    }
    
    // Don't alert if threshold not properly set
    if (rssiThreshold < -200) {
        return false;
    }
    
    // Check if we have recent RSSI data
    if (millis() - lastRssiUpdate > RSSI_TIMEOUT) {
        return false;
    }
    
    // Main alert condition: RSSI below threshold
    if (currentRSSI < rssiThreshold) {
        // Only log alert detection occasionally to reduce spam
        static unsigned long lastAlertLog = 0;
        unsigned long currentTime = millis();
        
        if (currentTime - lastAlertLog > 10000) { // Log every 10 seconds max
            Serial.printf("DISTANCE ALERT! RSSI: %d < %d | Tracking: %s | Alarm: %s\n", 
                         currentRSSI, rssiThreshold, trackingEnabled ? "ON" : "OFF", alarmEnabled ? "ON" : "OFF");
            lastAlertLog = currentTime;
        }
        return true;
    }
    
    return false;
}

bool shouldSendSMS() {
    unsigned long currentTime = millis();
    
    if (currentTime - lastSMSAlertTime < SMS_ALERT_COOLDOWN) {
        Serial.println("SMS cooldown active - skipping");
        return false;
    }
    
    if (bleConnected) {
        Serial.println("BLE connected - SMS not needed");
        return false;
    }
    
    if (WiFi.softAPgetStationNum() > 0) {
        Serial.println("WiFi available - SMS not needed");
        return false;
    }
    
    Serial.println("Both BLE and WiFi down - SMS needed");
    return true;
}

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        bleConnected = true;
        deviceConnected = true;
        digitalWrite(BLE_LED_PIN, HIGH);
        Serial.println("BLE Client connected!");
        
        currentRSSI = 0;
        lastRssiUpdate = millis();
    }
    
    void onDisconnect(BLEServer* pServer) {
        bleConnected = false;
        deviceConnected = false;
        currentRSSI = 0;
        lastRssiUpdate = 0;
        digitalWrite(BLE_LED_PIN, LOW);
        Serial.println("BLE Client disconnected!");
        
        if (trackingEnabled) {
            // Check if alarm should trigger (not silenced)
            if (alarmEnabled && !isAlarmSilenced()) {
                startBuzzer();
                Serial.println("BLE disconnect - alarm triggered");
            } else if (isAlarmSilenced()) {
                Serial.println("BLE disconnect - alarm silenced, no buzzer");
            } else {
                Serial.println("BLE disconnect - alarm disabled, no buzzer");
            }
            
            if (shouldSendSMS()) {
                alarmTriggered = true;
            }
        }
        
        delay(500);
        pServer->getAdvertising()->start();
        Serial.println("BLE advertising restarted");
    }
};

class MyCharacteristicCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pCharacteristic) {
        String value = pCharacteristic->getValue().c_str();
        if (value.length() > 0) {
            bool shouldLog = value.startsWith("ALARM") || value == "PING" || 
                           value == "LOCATION" || value.startsWith("SET_THRESHOLD") ||
                           value.startsWith("ANDROID_RSSI") || value == "REQUEST_SMS" ||
                           value == "SILENCE_ALARM" || value.startsWith("INIT_THRESHOLD") ||
                           value == "REQUEST_GPS" || value == "START_TRACKING" || value == "STOP_TRACKING";
            
            if (shouldLog) {
                Serial.printf("BLE Command: %s\n", value.c_str());
            }
            
            if (value.startsWith("INIT_THRESHOLD;")) {
                int semicolonIndex = value.indexOf(';');
                if (semicolonIndex > 0) {
                    String thresholdStr = value.substring(semicolonIndex + 1);
                    int newThreshold = thresholdStr.toInt();
                    
                    if (newThreshold >= -100 && newThreshold <= -30) {
                        rssiThreshold = newThreshold;
                        Serial.printf("Initial threshold set from app: %d dBm\n", rssiThreshold);
                        
                        String response = "THRESHOLD_INITIALIZED;" + String(rssiThreshold);
                        pAlertChar->setValue(response.c_str());
                        pAlertChar->notify();
                    }
                }
            }
            else if (value.startsWith("SET_THRESHOLD;")) {
                int semicolonIndex = value.indexOf(';');
                if (semicolonIndex > 0) {
                    String thresholdStr = value.substring(semicolonIndex + 1);
                    int newThreshold = thresholdStr.toInt();
                    
                    if (newThreshold >= -100 && newThreshold <= -30) {
                        rssiThreshold = newThreshold;
                        Serial.printf("Distance threshold updated: %d dBm\n", rssiThreshold);
                        
                        // CRITICAL: Automatically stop test when threshold is set manually
                        if (rssiTestMode || distanceCalibrating) {
                            rssiTestMode = false;
                            distanceCalibrating = false;
                            Serial.println("RSSI distance test auto-stopped after threshold set");
                        }
                        
                        String response = "THRESHOLD_SET;" + String(rssiThreshold);
                        pAlertChar->setValue(response.c_str());
                        pAlertChar->notify();
                    }
                }
            }
            else if (value == "START_RSSI_TEST") {
                rssiTestMode = true;
                distanceCalibrating = true;
                Serial.println("RSSI distance test STARTED - alerts disabled");
                String response = "RSSI_TEST_STARTED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "STOP_RSSI_TEST") {
                rssiTestMode = false;
                distanceCalibrating = false;
                Serial.println("RSSI distance test STOPPED - alerts enabled");
                String response = "RSSI_TEST_STOPPED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "SILENCE_ALARM") {
                Serial.println("SILENCE_ALARM command received via BLE");
                silenceAlarmPermanent();
                String response = "ALARM_SILENCED;5_MINUTES";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
                Serial.println("Alarm silenced for 5 minutes via BLE");
            }
            else if (value.startsWith("ANDROID_RSSI;")) {
                int semicolonIndex = value.indexOf(';');
                if (semicolonIndex > 0) {
                    String rssiStr = value.substring(semicolonIndex + 1);
                    int androidRSSI = rssiStr.toInt();
                    
                    if (androidRSSI != 0 && androidRSSI >= -100 && androidRSSI <= -20) {
                        currentRSSI = androidRSSI;
                        lastRssiUpdate = millis();
                        
                        if (rssiTestMode || distanceCalibrating) {
                            Serial.printf("RSSI updated: %d dBm (testing)\n", androidRSSI);
                        } else {
                            // Only log RSSI changes or important status changes
                            static int lastLoggedRSSI = 0;
                            static unsigned long lastRSSILog = 0;
                            
                            bool shouldLog = (abs(androidRSSI - lastLoggedRSSI) >= 2) || // 2dBm change
                                           (millis() - lastRSSILog > 30000) || // Every 30 seconds
                                           ((androidRSSI <= rssiThreshold) != (lastLoggedRSSI <= rssiThreshold)); // Status change
                            
                            if (shouldLog) {
                                String status = (androidRSSI > rssiThreshold) ? "SAFE" : "ALERT_ZONE";
                                Serial.printf("RSSI: %d dBm | Alert: %d dBm | %s | Track: %s\n", 
                                            androidRSSI, rssiThreshold, status.c_str(), trackingEnabled ? "ON" : "OFF");
                                lastLoggedRSSI = androidRSSI;
                                lastRSSILog = millis();
                            }
                        }
                    }
                }
            }
            else if (value == "START_TRACKING") {
                trackingEnabled = true;
                Serial.println(">> Tracking ENABLED via BLE <<");
                String response = "TRACKING_STATUS;ENABLED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "STOP_TRACKING") {
                trackingEnabled = false;
                Serial.println(">> Tracking DISABLED via BLE <<");
                String response = "TRACKING_STATUS;DISABLED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "REQUEST_GPS") {
                String response = "GPS_DATA;";
                if (gpsFixed) {
                    response += "LAT=" + String(lastLat, 6) + ";LON=" + String(lastLon, 6);
                } else {
                    response += "NO_FIX";
                }
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
                Serial.println("GPS data sent via BLE");
            }
            else if (value == "PING") {
                String response = "PONG";
                if (gpsFixed) {
                    response += ";LAT=" + String(lastLat, 6) + ";LON=" + String(lastLon, 6);
                }
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "ALARM_ON") {
                alarmEnabled = true;
                alarmSilenced = false;
                Serial.println("Alarm system ENABLED via BLE");
                String response = "ALARM_STATUS;ENABLED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "ALARM_OFF") {
                alarmEnabled = false;
                buzzerActive = false;
                alarmTriggered = false;
                digitalWrite(BUZZER_PIN, LOW);
                Serial.println("Alarm system DISABLED via BLE");
                String response = "ALARM_STATUS;DISABLED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(1000);
    
    Serial.println("\n=== ESP32 SmartTracker ===");
    
    pinMode(BLE_LED_PIN, OUTPUT);
    pinMode(WIFI_LED_PIN, OUTPUT);
    pinMode(GSM_LED_PIN, OUTPUT);
    pinMode(BUZZER_PIN, OUTPUT);
    
    digitalWrite(BLE_LED_PIN, LOW);
    digitalWrite(WIFI_LED_PIN, LOW);
    digitalWrite(GSM_LED_PIN, LOW);
    digitalWrite(BUZZER_PIN, LOW);
    
    setupBLE();
    setupWiFiAP();
    setupGSMGPS();
    
    currentState = SCAN_BLE;
    stateStart = millis();
    
    Serial.println("All systems initialized!");
    Serial.println("Alarm system: ENABLED by default");
    Serial.println("Tracking: DISABLED by default (enable via app)");
    
    for(int i = 0; i < 3; i++) {
        digitalWrite(BLE_LED_PIN, HIGH);
        digitalWrite(WIFI_LED_PIN, HIGH);
        digitalWrite(GSM_LED_PIN, HIGH);
        delay(150);
        digitalWrite(BLE_LED_PIN, LOW);
        digitalWrite(WIFI_LED_PIN, LOW);
        digitalWrite(GSM_LED_PIN, LOW);
        delay(150);
    }
}

void loop() {
    unsigned long currentTime = millis();
    
    if (currentTime - lastGpsRead >= GPS_READ_INTERVAL) {
        readGPS();
        lastGpsRead = currentTime;
    }
    
    updateLEDStatus();
    
    if (currentTime - lastStatusPrint >= STATUS_PRINT_INTERVAL) {
        printDetailedStatus();
        lastStatusPrint = currentTime;
    }
    
    handleBuzzer();
    
    processStateMachine();
    
    checkUDPPackets();
    
    delay(100);
}

void processStateMachine() {
    unsigned long currentTime = millis();
    
    switch(currentState) {
        case INIT:
            currentState = SCAN_BLE;
            stateStart = currentTime;
            break;
            
        case SCAN_BLE:
            if (bleConnected) {
                Serial.println("BLE: Client connected");
                currentState = CHECK_DISTANCE;
                stateStart = currentTime;
            } else if (currentTime - stateStart > STATE_TIMEOUT) {
                Serial.println("BLE: Timeout, switching to WiFi");
                currentState = SCAN_WIFI;
                stateStart = currentTime;
            }
            break;
            
        case CHECK_DISTANCE:
            if (!bleConnected) {
                Serial.println("BLE: Connection lost");
                if (trackingEnabled) {
                    // Check silence before triggering alarm
                    if (alarmEnabled && !isAlarmSilenced()) {
                        startBuzzer();
                    }
                    if (shouldSendSMS()) {
                        currentState = SCAN_GSM;
                    } else {
                        currentState = SCAN_WIFI;
                    }
                } else {
                    currentState = SCAN_WIFI;
                }
                stateStart = currentTime;
            } else if (trackingEnabled && checkDistanceAlert()) {
                // Alert cooldown per state cycle - more flexible
                Serial.printf("Distance alert detected! Tracking: %s, Alarm: %s, Silenced: %s\n", 
                            trackingEnabled ? "ON" : "OFF", alarmEnabled ? "ON" : "OFF", 
                            isAlarmSilenced() ? "YES" : "NO");
                
                // Only trigger buzzer if not silenced
                if (alarmEnabled && !isAlarmSilenced()) {
                    startBuzzer();
                }
                currentState = ALERT_SEND_BLE;
                stateStart = currentTime;
            }
            break;
            
        case ALERT_SEND_BLE:
            if (bleConnected && trackingEnabled) {
                unsigned long currentTime = millis();
                
                // BLE alert with cooldown
                if (currentTime - lastBLEAlertTime > BLE_ALERT_COOLDOWN) {
                    String alertMsg = "ALERT: Device moved away! RSSI: " + String(currentRSSI) + " < " + String(rssiThreshold);
                    if (gpsFixed) {
                        alertMsg += " Location: " + String(lastLat, 6) + "," + String(lastLon, 6);
                    }
                    pAlertChar->setValue(alertMsg.c_str());
                    pAlertChar->notify();
                    Serial.println("BLE alert sent: " + alertMsg);
                    lastBLEAlertTime = currentTime;
                }
                
                // Return to CHECK_DISTANCE after sending alert
                currentState = CHECK_DISTANCE;
                stateStart = currentTime;
            } else {
                Serial.println("BLE alert skipped - no connection or tracking disabled");
                currentState = SCAN_WIFI;
                stateStart = currentTime;
            }
            break;
            
        case SCAN_WIFI:
            if (WiFi.softAPgetStationNum() > 0) {
                Serial.println("WiFi: Client connected");
                currentState = WIFI_LISTEN;
                stateStart = currentTime;
            } else if (currentTime - stateStart > STATE_TIMEOUT) {
                Serial.println("WiFi: Timeout");
                if (shouldSendSMS() && alarmTriggered) {
                    Serial.println("WiFi timeout + alert pending = switching to GSM");
                    currentState = SCAN_GSM;
                } else {
                    Serial.println("WiFi timeout but no alert needed");
                    currentState = RECONNECT_SCAN;
                }
                stateStart = currentTime;
            }
            break;
            
        case WIFI_LISTEN:
            if (WiFi.softAPgetStationNum() == 0) {
                Serial.println("WiFi: Client disconnected");
                if (shouldSendSMS()) {
                    alarmTriggered = true;
                    currentState = SCAN_GSM;
                } else {
                    currentState = RECONNECT_SCAN;
                }
                stateStart = currentTime;
            }
            else if (alarmTriggered && trackingEnabled) {
                unsigned long currentTime = millis();
                
                if (currentTime - lastWiFiAlertTime > WIFI_ALERT_COOLDOWN) {
                    Serial.println("Sending alert via WiFi/UDP");
                    WiFiUDP alertUdp;
                    alertUdp.begin(5001);
                    alertUdp.beginPacket("255.255.255.255", 5001);
                    String alertMsg = "TRACKER_ALERT:Device_moved_away";
                    if (gpsFixed) {
                        alertMsg += ";LAT=" + String(lastLat, 6) + ";LON=" + String(lastLon, 6);
                    }
                    alertUdp.print(alertMsg);
                    alertUdp.endPacket();
                    alertUdp.stop();
                    
                    Serial.println("WiFi alert sent, flag cleared");
                    lastWiFiAlertTime = currentTime;
                }
                
                alarmTriggered = false;
            }
            break;
            
        case SCAN_GSM:
            if (checkGSMStatus()) {
                Serial.println("GSM: Network available");
                
                if (alarmTriggered && shouldSendSMS()) {
                    Serial.println("Conditions met for SMS alert");
                    currentState = ALERT_SEND_GSM;
                } else {
                    currentState = RECONNECT_SCAN;
                }
                stateStart = currentTime;
            } else if (currentTime - stateStart > STATE_TIMEOUT) {
                Serial.println("GSM: Timeout, all systems down");
                currentState = ALL_DOWN;
                stateStart = currentTime;
            }
            break;
            
        case ALERT_SEND_GSM:
            if (checkGSMStatus()) {
                String smsMsg = "SmartTracker ALERT: Your item is moving away! ";
                if (gpsFixed) {
                    smsMsg += "Location: https://maps.google.com/?q=" + String(lastLat, 6) + "," + String(lastLon, 6);
                } else {
                    smsMsg += "GPS location not available.";
                }
                sendSMSAlert(smsMsg);
                lastSMSAlertTime = currentTime;
                alarmTriggered = false;
                Serial.println("SMS alert sent via GSM");
            }
            currentState = RECONNECT_SCAN;
            stateStart = currentTime;
            break;
            
        case ALL_DOWN:
            if (currentTime - stateStart < 1000) {
                Serial.println("ALL_DOWN: No connections - waiting for recovery");
            }
            
            if (currentTime - stateStart > SLEEP_DURATION) {
                Serial.println("ALL_DOWN: Recovery attempt...");
                currentState = RECONNECT_SCAN;
                stateStart = currentTime;
            }
            return;
            
        case RECONNECT_SCAN:
            Serial.println("RECONNECT: Attempting to reconnect...");
            currentState = SCAN_BLE;
            stateStart = currentTime;
            break;
            
        case SLEEP_MODE:
            if (currentTime - stateStart < 1000) {
                Serial.println("SLEEP_MODE: Entering low power state");
            }
            
            if (currentTime - stateStart > SLEEP_DURATION) {
                Serial.println("SLEEP_MODE: Wake up, resuming operations");
                currentState = SCAN_BLE;
                stateStart = currentTime;
            }
            return;
            
        case ALARM_ACTIVE:
            handleBuzzer();
            if (!buzzerActive) {
                currentState = CHECK_DISTANCE;
                stateStart = currentTime;
            }
            break;
    }
}

void setupBLE() {
    Serial.println("Initializing BLE...");
    
    BLEDevice::init("ESP32_TRACKER");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    
    BLEService *pService = pServer->createService(BLE_SERVICE_UUID);
    
    pAlertChar = pService->createCharacteristic(
        BLE_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    
    pAlertChar->setCallbacks(new MyCharacteristicCallbacks());
    pAlertChar->addDescriptor(new BLE2902());
    
    pService->start();
    
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(BLE_SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    
    BLEDevice::startAdvertising();
    
    Serial.println("BLE server started - advertising ESP32_TRACKER");
}

void setupWiFiAP() {
    Serial.println("Setting up WiFi AP...");
    
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);
    
    IPAddress IP = WiFi.softAPIP();
    Serial.printf("WiFi AP ready - IP: %s, SSID: %s\n", IP.toString().c_str(), AP_SSID);
    
    udp.begin(UDP_PORT);
    Serial.printf("UDP server listening on port %d\n", UDP_PORT);
}

void setupGSMGPS() {
    Serial.println("Initializing GSM/GPS modules...");
    
    gsmSerial.begin(9600, SERIAL_8N1, GSM_RX_PIN, GSM_TX_PIN);
    delay(3000);
    
    Serial.println("Configuring GSM module...");
    gsmSerial.println("AT+CFUN=1,1");
    delay(5000);
    
    gsmSerial.println("ATE0");
    delay(1000);
    while (gsmSerial.available()) gsmSerial.read();
    
    gsmSerial.println("AT+CMGF=1");
    delay(1000);
    while (gsmSerial.available()) gsmSerial.read();
    
    gsmSerial.println("AT+CSCS=\"GSM\"");
    delay(1000);
    while (gsmSerial.available()) gsmSerial.read();
    
    Serial.println("Setting SMS center...");
    gsmSerial.println("AT+CSCA=\"+902322455667\"");
    delay(2000);
    while (gsmSerial.available()) gsmSerial.read();
    
    gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, -1);
    delay(1000);
    
    gsmSerial.println("AT");
    delay(2000);
    if (gsmSerial.available()) {
        String response = gsmSerial.readString();
        if (response.indexOf("OK") >= 0) {
            Serial.println("GSM module: OK");
        } else {
            Serial.println("GSM module: Response received but not optimal");
        }
    } else {
        Serial.println("GSM module: No response - check connections");
    }
    
    Serial.println("GSM/GPS initialization complete");
}

void readGPS() {
    while (gpsSerial.available() > 0) {
        if (gps.encode(gpsSerial.read())) {
            if (gps.location.isValid()) {
                lastLat = gps.location.lat();
                lastLon = gps.location.lng();
                gpsFixed = true;
                lastGpsUpdate = millis();
                
                static unsigned long lastGpsPrint = 0;
                if (millis() - lastGpsPrint > 120000) {
                    Serial.printf("GPS: %.6f, %.6f (sats: %d)\n", 
                                lastLat, lastLon, gps.satellites.value());
                    lastGpsPrint = millis();
                }
            }
        }
    }
    
    if (millis() - lastGpsUpdate > 180000) {
        gpsFixed = false;
    }
}

bool checkGSMStatus() {
    gsmSerial.println("AT+CSQ");
    delay(1000);
    
    String response = "";
    unsigned long timeout = millis() + 3000;
    
    while (millis() < timeout && gsmSerial.available()) {
        response += gsmSerial.readString();
    }
    
    while (gsmSerial.available()) {
        gsmSerial.read();
    }
    
    if (response.indexOf("+CSQ:") >= 0 && response.indexOf("99,99") < 0) {
        return true;
    }
    
    return false;
}

void sendSMSAlert(String message) {
    if (!checkGSMStatus()) {
        Serial.println("GSM not available for SMS");
        return;
    }
    
    Serial.println("=== SMS SENDING ===");
    
    while (gsmSerial.available()) {
        gsmSerial.read();
    }
    delay(1000);
    
    Serial.println("Checking current SMS center...");
    gsmSerial.println("AT+CSCA?");
    delay(3000);
    
    String currentSMSC = "";
    while (gsmSerial.available()) {
        currentSMSC += gsmSerial.readString();
    }
    Serial.println("Current SMSC: " + currentSMSC);
    
    bool needSetSMSC = false;
    if (currentSMSC.indexOf("+CSCA:") < 0 || 
        currentSMSC.indexOf("\"\"") >= 0 || 
        currentSMSC.length() < 20) {
        needSetSMSC = true;
    }
    
    if (needSetSMSC) {
        Serial.println("Auto-detecting SMS center...");
        
        String smsCenters[] = {
            "+902322455667",
            "+902324455667",
            "+905001000000",
            "+905007770000",
            "+902165454500",
        };
        
        bool smscSet = false;
        for (int i = 0; i < 5; i++) {
            Serial.println("Trying SMS center [" + String(i+1) + "/5]: " + smsCenters[i]);
            
            gsmSerial.println("AT+CSCA=\"" + smsCenters[i] + "\"");
            delay(2000);
            
            String setResponse = "";
            while (gsmSerial.available()) {
                setResponse += gsmSerial.readString();
            }
            
            if (setResponse.indexOf("OK") >= 0) {
                Serial.println("SMS center set: " + smsCenters[i]);
                
                Serial.println("Testing SMS center with test message...");
                gsmSerial.println("AT+CMGS=\"" + alertPhoneNumber + "\"");
                delay(5000);
                
                String testPrompt = "";
                unsigned long testTimeout = millis() + 8000;
                bool gotTestPrompt = false;
                
                while (millis() < testTimeout) {
                    if (gsmSerial.available()) {
                        char c = gsmSerial.read();
                        testPrompt += c;
                        if (c == '>') {
                            gotTestPrompt = true;
                            break;
                        }
                    }
                    delay(50);
                }
                
                if (gotTestPrompt) {
                    Serial.println("SMS center working! Proceeding...");
                    gsmSerial.write(26);
                    delay(1000);
                    while (gsmSerial.available()) gsmSerial.read();
                    smscSet = true;
                    break;
                } else {
                    Serial.println("SMS center not working, trying next...");
                    gsmSerial.write(26);
                    delay(1000);
                    while (gsmSerial.available()) gsmSerial.read();
                }
            } else {
                Serial.println("Failed to set SMS center: " + smsCenters[i]);
            }
        }
        
        if (!smscSet) {
            Serial.println("NO WORKING SMS CENTER FOUND - SMS FAILED");
            return;
        }
    } else {
        Serial.println("Using existing SMS center");
    }
    
    Serial.println("Setting SMS text mode...");
    gsmSerial.println("AT+CMGF=1");
    delay(2000);
    
    String cmgfResponse = "";
    while (gsmSerial.available()) {
        cmgfResponse += gsmSerial.readString();
    }
    
    if (cmgfResponse.indexOf("OK") < 0) {
        Serial.println("Failed to set SMS text mode");
        return;
    }
    
    Serial.println("Sending SMS to: " + alertPhoneNumber);
    
    gsmSerial.print("AT+CMGS=\"");
    gsmSerial.print(alertPhoneNumber);
    gsmSerial.println("\"");
    
    String promptResponse = "";
    unsigned long promptTimeout = millis() + 8000;
    bool gotPrompt = false;
    
    while (millis() < promptTimeout) {
        if (gsmSerial.available()) {
            char c = gsmSerial.read();
            promptResponse += c;
            if (c == '>') {
                gotPrompt = true;
                Serial.println("\nGot SMS prompt!");
                break;
            }
        }
        delay(50);
    }
    
    if (!gotPrompt) {
        Serial.println("No SMS prompt - ABORTING");
        return;
    }
    
    String shortMessage = message;
    if (message.length() > 140) {
        shortMessage = message.substring(0, 137) + "...";
    }
    
    gsmSerial.print(shortMessage);
    delay(500);
    gsmSerial.write(26);
    
    String finalResponse = "";
    unsigned long deliveryTimeout = millis() + 15000;
    bool success = false;
    
    while (millis() < deliveryTimeout) {
        if (gsmSerial.available()) {
            char c = gsmSerial.read();
            finalResponse += c;
            
            if (finalResponse.indexOf("+CMGS:") >= 0) {
                success = true;
                Serial.println("\nSMS SENT SUCCESSFULLY!");
                break;
            }
            
            if (finalResponse.indexOf("ERROR") >= 0) {
                Serial.println("\nSMS ERROR: " + finalResponse);
                break;
            }
        }
        delay(100);
    }
    
    if (success) {
        Serial.println("SMS delivered successfully!");
        lastSMSAlertTime = millis();
    } else {
        Serial.println("SMS FAILED - Response: " + finalResponse);
        
        if (finalResponse.indexOf("CMS ERROR") >= 0) {
            Serial.println("DIAGNOSIS: SMS service error - check SIM credit/SMS center");
        } else if (finalResponse.indexOf("CME ERROR") >= 0) {
            Serial.println("DIAGNOSIS: Modem error - check network/SIM");
        } else if (finalResponse.length() == 0) {
            Serial.println("DIAGNOSIS: Network timeout - weak signal");
        } else {
            Serial.println("DIAGNOSIS: Unknown error - check SIM/network");
        }
    }
    
    while (gsmSerial.available()) {
        gsmSerial.read();
    }
    
    Serial.println("=== SMS PROCESS COMPLETE ===\n");
}

void checkUDPPackets() {
    int packetSize = udp.parsePacket();
    if (packetSize) {
        char incomingPacket[255];
        int len = udp.read(incomingPacket, 255);
        if (len > 0) {
            incomingPacket[len] = 0;
            String command = String(incomingPacket);
            
            bool shouldLog = command.startsWith("ALARM") || command == "PING_TEST" ||
                           command == "GET_GSM_STATUS" || command == "SILENCE_ALARM" ||
                           command == "REQUEST_SMS" || command == "REQUEST_GPS" ||
                           command.startsWith("SET_THRESHOLD") || command.startsWith("INIT_THRESHOLD") ||
                           command == "START_TRACKING" || command == "STOP_TRACKING";
            
            if (shouldLog) {
                Serial.printf("UDP Command: %s\n", command.c_str());
            }
            
            String response = "";
            
            if (command == "SILENCE_ALARM") {
                Serial.println("SILENCE_ALARM command received via UDP");
                silenceAlarmPermanent();
                response = "ALARM_SILENCED;5_MINUTES";
                Serial.println("Alarm silenced for 5 minutes via UDP");
            }

            else if (command.startsWith("INIT_THRESHOLD;")) {
                int semicolonIndex = command.indexOf(';');
                if (semicolonIndex > 0) {
                    String thresholdStr = command.substring(semicolonIndex + 1);
                    int newThreshold = thresholdStr.toInt();
                    
                    if (newThreshold >= -100 && newThreshold <= -30) {
                        rssiThreshold = newThreshold;
                        Serial.printf("Initial threshold set from WiFi: %d dBm\n", rssiThreshold);
                        response = "THRESHOLD_INITIALIZED;" + String(rssiThreshold);
                    }
                }
            }
            
            else if (command.startsWith("SET_THRESHOLD;")) {
                int semicolonIndex = command.indexOf(';');
                if (semicolonIndex > 0) {
                    String thresholdStr = command.substring(semicolonIndex + 1);
                    int newThreshold = thresholdStr.toInt();
                    
                    if (newThreshold >= -100 && newThreshold <= -30) {
                        rssiThreshold = newThreshold;
                        Serial.printf("Distance threshold updated via WiFi: %d dBm\n", rssiThreshold);
                        response = "THRESHOLD_SET;" + String(rssiThreshold);
                    }
                }
            }
            
            else if (command == "ALARM_ON") {
                alarmEnabled = true;
                Serial.println("Alarm system ENABLED via UDP");
                response = "ALARM_STATUS;ENABLED";
            }
            else if (command == "ALARM_OFF") {
                alarmEnabled = false;
                stopBuzzer();
                Serial.println("Alarm system DISABLED via UDP");
                response = "ALARM_STATUS;DISABLED";
            }
            else if (command == "START_TRACKING") {
                trackingEnabled = true;
                Serial.println("Tracking ENABLED via UDP");
                response = "TRACKING_STATUS;ENABLED";
            }
            else if (command == "STOP_TRACKING") {
                trackingEnabled = false;
                Serial.println("Tracking DISABLED via UDP");
                response = "TRACKING_STATUS;DISABLED";
            }
            else if (command == "REQUEST_GPS") {
                response = "GPS_DATA;";
                if (gpsFixed) {
                    response += "LAT=" + String(lastLat, 6) + ";LON=" + String(lastLon, 6);
                } else {
                    response += "NO_FIX";
                }
                Serial.println("GPS data sent via UDP");
            }
            else if (command == "REQUEST_SMS") {
                Serial.println("SMS requested via UDP - forcing send");
                if (checkGSMStatus()) {
                    String smsMsg = "SmartTracker STATUS: Your item is safe. ";
                    if (gpsFixed) {
                        smsMsg += "Location: https://maps.google.com/?q=" + String(lastLat, 6) + "," + String(lastLon, 6);
                    } else {
                        smsMsg += "GPS location not available.";
                    }
                    sendSMSAlert(smsMsg);
                    response = "SMS_SENT";
                } else {
                    response = "SMS_FAILED;NO_GSM";
                }
            }
            else if (command == "GET_GSM_STATUS") {
                bool gsmOK = checkGSMStatus();
                response = "GSM_STATUS;" + String(gsmOK ? "CONNECTED" : "DISCONNECTED");
            }
            else if (command == "PING_TEST") {
                response = "PING_RESPONSE;OK";
                if (gpsFixed) {
                    response += ";LAT=" + String(lastLat, 6) + ";LON=" + String(lastLon, 6);
                }
            }
            else {
                response = "UNKNOWN_COMMAND";
            }
            
            udp.beginPacket(udp.remoteIP(), udp.remotePort());
            udp.print(response);
            udp.endPacket();
        }
    }
}

void printDetailedStatus() {
    Serial.println("\n=== SYSTEM STATUS ===");
    Serial.printf("State: %s\n", getStateName(currentState).c_str());
    Serial.printf("BLE: %s", bleConnected ? "Connected" : "Searching");
    if (bleConnected) {
        Serial.printf(" (RSSI: %d dBm)", currentRSSI);
    }
    Serial.println();
    Serial.printf("WiFi AP: %d clients connected\n", WiFi.softAPgetStationNum());
    Serial.printf("GSM: %s\n", checkGSMStatus() ? "Network ready" : "No network");
    Serial.printf("GPS: %s", gpsFixed ? "Fixed" : "Searching");
    if (gpsFixed) {
        Serial.printf(" (%.6f, %.6f)", lastLat, lastLon);
    }
    Serial.println();
    Serial.printf("Alarm: %s", alarmEnabled ? (buzzerActive ? "ACTIVE" : "ENABLED") : "DISABLED");
    Serial.printf(" | Tracking: %s", trackingEnabled ? "ENABLED" : "DISABLED");
    if (alarmEnabled) {
        Serial.printf(" | Threshold: %d dBm", rssiThreshold);
    }
    Serial.println();
    Serial.printf("Uptime: %lu minutes | Free heap: %d bytes\n", 
                 millis() / 60000, ESP.getFreeHeap());
    Serial.println("====================\n");
}

String getStateName(TrackerState state) {
    switch (state) {
        case INIT: return "INIT";
        case SCAN_BLE: return "SCAN_BLE";
        case CHECK_DISTANCE: return "CHECK_DISTANCE";
        case ALERT_SEND_BLE: return "ALERT_SEND_BLE";
        case SCAN_WIFI: return "SCAN_WIFI";
        case WIFI_LISTEN: return "WIFI_LISTEN";
        case SCAN_GSM: return "SCAN_GSM";
        case ALERT_SEND_GSM: return "ALERT_SEND_GSM";
        case ALL_DOWN: return "ALL_DOWN";
        case RECONNECT_SCAN: return "RECONNECT_SCAN";
        case SLEEP_MODE: return "SLEEP_MODE";
        case ALARM_ACTIVE: return "ALARM_ACTIVE";
        default: return "UNKNOWN";
    }
}