#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <HardwareSerial.h>
#include <TinyGPSPlus.h>

// --- PIN Definitions ---
#define GSM_RX_PIN 16        // SIM808 TXD → ESP32 RX2
#define GSM_TX_PIN 17        // SIM808 RXD → ESP32 TX2
#define GPS_RX_PIN 18        // Neo-6M TXD → ESP32
#define BUZZER_PIN 27        // Physical buzzer pin

// Status LED Pins
#define BLE_LED_PIN 33
#define WIFI_LED_PIN 25
#define GSM_LED_PIN 26

// BLE UUIDs
#define BLE_SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define BLE_CHAR_UUID    "abcd1234-ab12-cd34-ef00-1234567890ab"

// Wi‑Fi AP Settings
const char* AP_SSID = "ESP_TRACKER";
const char* AP_PASS = "12345678";
#define UDP_PORT 5000

// GSM and GPS Modules
HardwareSerial gsmSerial(2);    // UART2 for SIM808
HardwareSerial gpsSerial(1);    // UART1 for Neo-6M
TinyGPSPlus gps;

// --- Alarm System Variables - DÜZELTME: Silence state eklendi
bool alarmEnabled = true;           
bool buzzerActive = false;          
bool alarmTriggered = false;
bool alarmSilenced = false;         // DÜZELTME: Silence durumu
bool distanceCalibrating = false;   // DÜZELTME: Calibration sırasında alarm yok
unsigned long buzzerStartTime = 0;  
unsigned long lastBuzzerBeep = 0;   
unsigned long silenceTime = 0;     // DÜZELTME: Silence zamanı
const unsigned long BUZZER_DURATION = 30000;
const unsigned long BEEP_INTERVAL = 1000;
const unsigned long SILENCE_DURATION = 300000; // 5 dakika silence

// --- RSSI Management - DÜZELTME: Başlangıç threshold çok düşük
bool rssiTestMode = false;
int currentRSSI = 0;               
int rssiThreshold = -1000;         // DÜZELTME: Başta çok düşük, hemen ötmesin
unsigned long lastRssiUpdate = 0;
const unsigned long RSSI_TIMEOUT = 10000;

// --- Alert spam prevention - DÜZELTME: Cooldown süreleri optimize ---
unsigned long lastBLEAlertTime = 0;
unsigned long lastWiFiAlertTime = 0;
unsigned long lastSMSAlertTime = 0;
const unsigned long BLE_ALERT_COOLDOWN = 5000;   // 5 saniye
const unsigned long WIFI_ALERT_COOLDOWN = 10000; // 10 saniye
const unsigned long SMS_ALERT_COOLDOWN = 60000;  // 60 saniye

// --- State Machine ---
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

// --- Global Variables ---
BLEServer *pServer;
BLECharacteristic *pAlertChar;
bool bleConnected = false;
bool deviceConnected = false;

WiFiUDP udp;

// GPS Variables
double lastLat = 0.0;  
double lastLon = 0.0;
bool gpsFixed = false;
unsigned long lastGpsUpdate = 0;

// State Machine Timers
unsigned long stateStart = 0;
unsigned long lastStatusPrint = 0;
unsigned long lastGpsRead = 0;

// Phone number for SMS alerts
const String alertPhoneNumber = "+905447661357"; 

// Timing Constants
const unsigned long STATE_TIMEOUT = 30000;       // 30 seconds for BLE/WiFi/GSM
const unsigned long SLEEP_DURATION = 30000;    
const unsigned long GPS_READ_INTERVAL = 20000;   
const unsigned long STATUS_PRINT_INTERVAL = 300000; // 5 minutes

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
    
    // 5 dakika geçmişse silence'ı kaldır
    if (millis() - silenceTime > SILENCE_DURATION) {
        alarmSilenced = false;
        Serial.println("Alarm silence period ended - monitoring resumed");
        return false;
    }
    return true;
}


// DÜZELTME: Buzzer kontrol - silence ve calibration check
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

// DÜZELTME: Stop buzzer - hem lokal hem remote çağrılabilir
void stopBuzzer() {
    if (!buzzerActive && !alarmTriggered) return; // Zaten durmuşsa işlem yapma
    
    buzzerActive = false;
    alarmTriggered = false;
    digitalWrite(BUZZER_PIN, LOW);
    Serial.println("BUZZER STOPPED - Alarm silenced");
}

void handleBuzzer() {
    if (!buzzerActive || !alarmEnabled || isAlarmSilenced()) return;
    
    unsigned long currentTime = millis();
    
    // Auto stop after 30 seconds
    if (currentTime - buzzerStartTime > BUZZER_DURATION) {
        buzzerActive = false;
        alarmTriggered = false;
        digitalWrite(BUZZER_PIN, LOW);
        Serial.println("BUZZER AUTO-STOPPED after 30 seconds");
        return;
    }
    
    // Beep every second
    if (currentTime - lastBuzzerBeep > BEEP_INTERVAL) {
        digitalWrite(BUZZER_PIN, HIGH);
        delay(200);
        digitalWrite(BUZZER_PIN, LOW);
        lastBuzzerBeep = currentTime;
    }
}

// Update LED status indicators
void updateLEDStatus() {
    // BLE LED
    digitalWrite(BLE_LED_PIN, bleConnected ? HIGH : LOW);
    
    // WiFi LED  
    bool wifiActive = (WiFi.softAPgetStationNum() > 0);
    digitalWrite(WIFI_LED_PIN, wifiActive ? HIGH : LOW);
    
    // GSM LED - Check less frequently
    static unsigned long lastGSMCheck = 0;
    static bool gsmStatus = false;
    
    if (millis() - lastGSMCheck > 30000) { // Every 30 seconds
        gsmStatus = checkGSMStatus();
        lastGSMCheck = millis();
    }
    
    digitalWrite(GSM_LED_PIN, gsmStatus ? HIGH : LOW);
}

// DÜZELTME: Distance alert check - calibration sırasında çalışmasın
bool checkDistanceAlert() {
    if (!bleConnected || !deviceConnected || distanceCalibrating) {
        return false;
    }
    
    // Threshold çok düşükse (ilk açılış) alert verme
    if (rssiThreshold < -200) {
        return false;
    }
    
    // Check if we have recent RSSI data
    if (millis() - lastRssiUpdate > RSSI_TIMEOUT) {
        Serial.println("RSSI data timeout - requesting update");
        return false;
    }
    
    // Check if device is too far
    if (currentRSSI < rssiThreshold) {
        Serial.printf("DISTANCE ALERT! RSSI: %d < %d\n", currentRSSI, rssiThreshold);
        return true;
    }
    
    return false;
}

// DÜZELTME: SMS gönderim durumunu kontrol eden global fonksiyon
bool shouldSendSMS() {
    unsigned long currentTime = millis();
    
    // SMS cooldown kontrolü
    if (currentTime - lastSMSAlertTime < SMS_ALERT_COOLDOWN) {
        Serial.println("SMS cooldown active - skipping");
        return false;
    }
    
    // BLE bağlıysa SMS gönderme (çünkü cihaz yakında)
    if (bleConnected) {
        Serial.println("BLE connected - SMS not needed");
        return false;
    }
    
    // WiFi varsa ve BLE yoksa SMS gönderme, WiFi ile bildirim yeterli
    if (WiFi.softAPgetStationNum() > 0) {
        Serial.println("WiFi available - SMS not needed");
        return false;
    }
    
    // SADECE BLE yok VE WiFi yok ise SMS gönder
    Serial.println("Both BLE and WiFi down - SMS needed");
    return true;
}

// --- BLE Server Callbacks ---
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
        
        // DÜZELTME: BLE kopunca sadece alarm aktifse buzzer çal
        if (alarmEnabled) {
            startBuzzer();
        }
        
        // DÜZELTME: SMS gönderilip gönderilmeyeceğini kontrol et
        if (shouldSendSMS()) {
            alarmTriggered = true; // SMS için flag set et
        }
        
        delay(500);
        pServer->getAdvertising()->start();
        Serial.println("BLE advertising restarted");
    }
};

// BLE Characteristic Callbacks - DÜZELTME: Threshold ve Silence komutları
class MyCharacteristicCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pCharacteristic) {
        String value = pCharacteristic->getValue().c_str();
        if (value.length() > 0) {
            bool shouldLog = value.startsWith("ALARM") || value == "PING" || 
                           value == "LOCATION" || value.startsWith("SET_THRESHOLD") ||
                           value.startsWith("ANDROID_RSSI") || value == "REQUEST_SMS" ||
                           value == "SILENCE_ALARM" || value.startsWith("INIT_THRESHOLD");
            
            if (shouldLog) {
                Serial.printf("BLE Command: %s\n", value.c_str());
            }
            
            // DÜZELTME: İlk threshold ayarı
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
            // Normal threshold setting
            else if (value.startsWith("SET_THRESHOLD;")) {
                int semicolonIndex = value.indexOf(';');
                if (semicolonIndex > 0) {
                    String thresholdStr = value.substring(semicolonIndex + 1);
                    int newThreshold = thresholdStr.toInt();
                    
                    if (newThreshold >= -100 && newThreshold <= -30) {
                        rssiThreshold = newThreshold;
                        Serial.printf("Distance threshold updated: %d dBm\n", rssiThreshold);
                        
                        String response = "THRESHOLD_SET;" + String(rssiThreshold);
                        pAlertChar->setValue(response.c_str());
                        pAlertChar->notify();
                    }
                }
            }
            // DÜZELTME: Distance calibration start/stop
            else if (value == "START_RSSI_TEST") {
                rssiTestMode = true;
                distanceCalibrating = true; // DÜZELTME: Calibration flag
                Serial.println("RSSI distance test STARTED - alerts disabled");
                String response = "RSSI_TEST_STARTED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            else if (value == "STOP_RSSI_TEST") {
                rssiTestMode = false;
                distanceCalibrating = false; // DÜZELTME: Calibration flag
                Serial.println("RSSI distance test STOPPED - alerts enabled");
                String response = "RSSI_TEST_STOPPED";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            // DÜZELTME: Silence alarm komutu
            else if (value == "SILENCE_ALARM") {
                Serial.println("SILENCE_ALARM command received via BLE");
                silenceAlarmPermanent();
                String response = "ALARM_SILENCED;5_MINUTES";
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
                Serial.println("Alarm silenced for 5 minutes via BLE");
            }
            // DÜZELTME: Android RSSI - calibration sırasında log
            else if (value.startsWith("ANDROID_RSSI;")) {
                int semicolonIndex = value.indexOf(';');
                if (semicolonIndex > 0) {
                    String rssiStr = value.substring(semicolonIndex + 1);
                    int androidRSSI = rssiStr.toInt();
                    
                    if (androidRSSI != 0 && androidRSSI >= -100 && androidRSSI <= -20) {
                        currentRSSI = androidRSSI;
                        lastRssiUpdate = millis();
                        
                        // Log during calibration or test mode
                        if (rssiTestMode || distanceCalibrating) {
                            Serial.printf("RSSI updated: %d dBm\n", androidRSSI);
                        }
                    }
                }
            }
            // Diğer komutlar...
            else if (value == "PING") {
                String response = "PONG";
                if (gpsFixed) {
                    response += ";LAT=" + String(lastLat, 6) + ";LON=" + String(lastLon, 6);
                }
                pAlertChar->setValue(response.c_str());
                pAlertChar->notify();
            }
            // Alarm enable/disable
            else if (value == "ALARM_ON") {
                alarmEnabled = true;
                alarmSilenced = false; // Silence'ı kaldır
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
    
    Serial.println("\n=== ESP32 Smart Tracker v2.1 Production ===");
    Serial.println("FIXES: SMS delivery + Alarm control + WiFi logic");
    Serial.println("===========================================");
    
    // Pin configurations
    pinMode(BLE_LED_PIN, OUTPUT);
    pinMode(WIFI_LED_PIN, OUTPUT);
    pinMode(GSM_LED_PIN, OUTPUT);
    pinMode(BUZZER_PIN, OUTPUT);
    
    digitalWrite(BLE_LED_PIN, LOW);
    digitalWrite(WIFI_LED_PIN, LOW);
    digitalWrite(GSM_LED_PIN, LOW);
    digitalWrite(BUZZER_PIN, LOW);
    
    // Initialize modules
    setupBLE();
    setupWiFiAP();
    setupGSMGPS();
    
    // Initial state
    currentState = SCAN_BLE;
    stateStart = millis();
    
    Serial.println("All systems initialized - Production ready!");
    Serial.println("Alarm system: ENABLED by default");
    Serial.println("State machine: ACTIVE");
    Serial.println("===========================================\n");
    
    // Startup LED signal
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
    
    // Read GPS regularly
    if (currentTime - lastGpsRead >= GPS_READ_INTERVAL) {
        readGPS();
        lastGpsRead = currentTime;
    }
    
    // Update LED status
    updateLEDStatus();
    
    // Status printing - Less frequent
    if (currentTime - lastStatusPrint >= STATUS_PRINT_INTERVAL) {
        printDetailedStatus();
        lastStatusPrint = currentTime;
    }
    
    // Buzzer control
    handleBuzzer();
    
    // Main State Machine
    processStateMachine();
    
    // UDP packet check
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
                // Alarm kontrolü: sadece buzzer için
                if (alarmEnabled) {
                    startBuzzer();
                }
                // SMS gönderilmesi gerekip gerekmediğini kontrol et
                if (shouldSendSMS()) {
                    currentState = SCAN_GSM;
                } else {
                    currentState = SCAN_WIFI;
                }
                stateStart = currentTime;
            } else if (checkDistanceAlert()) {
                // Alarm kontrolü: sadece buzzer için
                if (alarmEnabled) {
                    startBuzzer();
                }
                // BLE varsa BLE üzerinden bildirim gönder
                currentState = ALERT_SEND_BLE;
                stateStart = currentTime;
            }
            break;
            
        case ALERT_SEND_BLE:
            if (bleConnected) {
                unsigned long currentTime = millis();
                
                // BLE alert spam kontrolü
                if (currentTime - lastBLEAlertTime > BLE_ALERT_COOLDOWN) {
                    String alertMsg = "ALERT: Device moved away! RSSI: " + String(currentRSSI);
                    if (gpsFixed) {
                        alertMsg += " Location: " + String(lastLat, 6) + "," + String(lastLon, 6);
                    }
                    pAlertChar->setValue(alertMsg.c_str());
                    pAlertChar->notify();
                    Serial.println("BLE alert sent");
                    lastBLEAlertTime = currentTime;
                }
                
                // BLE ile bildirim gönderildi, işlem tamamlandı
                currentState = CHECK_DISTANCE;
                stateStart = currentTime;
            } else {
                // BLE yoksa WiFi'ye geç
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
                // DÜZELTME: WiFi timeout'unda SMS gönderilmesi gerekiyorsa GSM'e geç
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
                // DÜZELTME: WiFi kopunca SMS gönderilmesi gerekiyorsa
                if (shouldSendSMS()) {
                    alarmTriggered = true;
                    currentState = SCAN_GSM;
                } else {
                    currentState = RECONNECT_SCAN;
                }
                stateStart = currentTime;
            }
            // WiFi bağlıyken bildirim gönderebilir
            else if (alarmTriggered) {
                unsigned long currentTime = millis();
                
                // WiFi alert spam kontrolü
                if (currentTime - lastWiFiAlertTime > WIFI_ALERT_COOLDOWN) {
                    // WiFi üzerinden bildirim gönder
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
                
                // Bildirim gönderildi, buzzer flag'ini sıfırla
                alarmTriggered = false;
            }
            break;
            
        case SCAN_GSM:
            if (checkGSMStatus()) {
                Serial.println("GSM: Network available");
                
                // DÜZELTME: SMS gönderilmesi gerekiyorsa ve cooldown geçmişse
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
                String smsMsg = "SMART TRACKER ALERT: Your item is moving away! ";
                if (gpsFixed) {
                    smsMsg += "Location: https://maps.google.com/?q=" + String(lastLat, 6) + "," + String(lastLon, 6);
                } else {
                    smsMsg += "GPS location not available.";
                }
                sendSMSAlert(smsMsg);
                lastSMSAlertTime = currentTime; // DÜZELTME: SMS cooldown timer set et
                alarmTriggered = false; // Flag clear
                Serial.println("SMS alert sent via GSM");
            }
            currentState = RECONNECT_SCAN;
            stateStart = currentTime;
            break;
            
        case ALL_DOWN:
            // Only print once when entering this state
            if (currentTime - stateStart < 1000) {
                Serial.println("ALL_DOWN: No connections - waiting for recovery");
            }
            
            // Wait before retrying
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
    
    // Initialize GSM (SIM808)
    gsmSerial.begin(9600, SERIAL_8N1, GSM_RX_PIN, GSM_TX_PIN);
    delay(3000);
    
    // GSM modülü reset ve temel ayarlar
    Serial.println("Configuring GSM module...");
    gsmSerial.println("AT+CFUN=1,1"); // Full reset
    delay(5000);
    
    // Temel AT komutları
    gsmSerial.println("ATE0"); // Echo off
    delay(1000);
    while (gsmSerial.available()) gsmSerial.read(); // Buffer temizle
    
    gsmSerial.println("AT+CMGF=1"); // SMS text mode
    delay(1000);
    while (gsmSerial.available()) gsmSerial.read();
    
    gsmSerial.println("AT+CSCS=\"GSM\""); // Character set
    delay(1000);
    while (gsmSerial.available()) gsmSerial.read();
    
    // DÜZELTME: Daha güvenilir SMS center ayarı
    Serial.println("Setting SMS center...");
    gsmSerial.println("AT+CSCA=\"+902322455667\""); // Turkcell SMS center
    delay(2000);
    while (gsmSerial.available()) gsmSerial.read();
    
    // Initialize GPS (Neo-6M)  
    gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, -1);
    delay(1000);
    
    // GSM test
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
                
                // GPS log'u daha az sıklıkla
                static unsigned long lastGpsPrint = 0;
                if (millis() - lastGpsPrint > 120000) { // Her 2 dakikada bir
                    Serial.printf("GPS: %.6f, %.6f (sats: %d)\n", 
                                lastLat, lastLon, gps.satellites.value());
                    lastGpsPrint = millis();
                }
            }
        }
    }
    
    // Check GPS timeout
    if (millis() - lastGpsUpdate > 180000) { // 3 minute timeout
        gpsFixed = false;
    }
}

bool checkGSMStatus() {
    gsmSerial.println("AT+CSQ"); // Check signal quality
    delay(1000);
    
    String response = "";
    unsigned long timeout = millis() + 3000;
    
    while (millis() < timeout && gsmSerial.available()) {
        response += gsmSerial.readString();
    }
    
    // Clear any remaining data
    while (gsmSerial.available()) {
        gsmSerial.read();
    }
    
    if (response.indexOf("+CSQ:") >= 0 && response.indexOf("99,99") < 0) {
        return true; // Good signal
    }
    
    return false;
}

// DÜZELTME: SMS gönderme - otomatik SMS center detection
void sendSMSAlert(String message) {
    if (!checkGSMStatus()) {
        Serial.println("GSM not available for SMS");
        return;
    }
    
    Serial.println("=== SMS SENDING v2.2 - AUTO SMS CENTER ===");
    
    // Buffer temizle
    while (gsmSerial.available()) {
        gsmSerial.read();
    }
    delay(1000);
    
    // DÜZELTME: Önce mevcut SMS center'ı kontrol et
    Serial.println("Checking current SMS center...");
    gsmSerial.println("AT+CSCA?");
    delay(3000);
    
    String currentSMSC = "";
    while (gsmSerial.available()) {
        currentSMSC += gsmSerial.readString();
    }
    Serial.println("Current SMSC: " + currentSMSC);
    
    // DÜZELTME: Eğer SMS center boş veya hatalıysa, otomatik tespit et
    bool needSetSMSC = false;
    if (currentSMSC.indexOf("+CSCA:") < 0 || 
        currentSMSC.indexOf("\"\"") >= 0 || 
        currentSMSC.length() < 20) {
        needSetSMSC = true;
    }
    
    if (needSetSMSC) {
        Serial.println("Auto-detecting SMS center...");
        
        // DÜZELTME: Türkiye operatör SMS center'ları - sırayla dene
        String smsCenters[] = {
            "+902322455667",  // Turkcell 1
            "+902324455667",  // Turkcell 2  
            "+905001000000",  // Vodafone
            "+905007770000",  // Türk Telekom
            "+902165454500",  // Avea/Türk Telekom 2
        };
        
        bool smscSet = false;
        for (int i = 0; i < 5; i++) {
            Serial.println("Trying SMS center [" + String(i+1) + "/5]: " + smsCenters[i]);
            
            // SMS center set et
            gsmSerial.println("AT+CSCA=\"" + smsCenters[i] + "\"");
            delay(2000);
            
            String setResponse = "";
            while (gsmSerial.available()) {
                setResponse += gsmSerial.readString();
            }
            
            if (setResponse.indexOf("OK") >= 0) {
                Serial.println("✅ SMS center set: " + smsCenters[i]);
                
                // Test SMS gönder (kendi numarana)
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
                    Serial.println("✅ SMS center working! Proceeding...");
                    gsmSerial.write(26); // Cancel test
                    delay(1000);
                    while (gsmSerial.available()) gsmSerial.read(); // Clean buffer
                    smscSet = true;
                    break;
                } else {
                    Serial.println("❌ SMS center not working, trying next...");
                    gsmSerial.write(26); // Cancel
                    delay(1000);
                    while (gsmSerial.available()) gsmSerial.read();
                }
            } else {
                Serial.println("❌ Failed to set SMS center: " + smsCenters[i]);
            }
        }
        
        if (!smscSet) {
            Serial.println("❌ NO WORKING SMS CENTER FOUND - SMS FAILED");
            return;
        }
    } else {
        Serial.println("✅ Using existing SMS center");
    }
    
    // DÜZELTME: SMS text mode
    Serial.println("Setting SMS text mode...");
    gsmSerial.println("AT+CMGF=1");
    delay(2000);
    
    String cmgfResponse = "";
    while (gsmSerial.available()) {
        cmgfResponse += gsmSerial.readString();
    }
    
    if (cmgfResponse.indexOf("OK") < 0) {
        Serial.println("❌ Failed to set SMS text mode");
        return;
    }
    
    // DÜZELTME: Gerçek SMS gönderimi - daha kısa timeout
    Serial.println("Sending SMS to: " + alertPhoneNumber);
    
    gsmSerial.print("AT+CMGS=\"");
    gsmSerial.print(alertPhoneNumber);
    gsmSerial.println("\"");
    
    // Prompt bekle - DÜZELTME: 8 saniye timeout
    String promptResponse = "";
    unsigned long promptTimeout = millis() + 8000;
    bool gotPrompt = false;
    
    while (millis() < promptTimeout) {
        if (gsmSerial.available()) {
            char c = gsmSerial.read();
            promptResponse += c;
            if (c == '>') {
                gotPrompt = true;
                Serial.println("\n✅ Got SMS prompt!");
                break;
            }
        }
        delay(50);
    }
    
    if (!gotPrompt) {
        Serial.println("❌ No SMS prompt - ABORTING");
        return;
    }
    
    // Mesaj gönder
    String shortMessage = message;
    if (message.length() > 140) {
        shortMessage = message.substring(0, 137) + "...";
    }
    
    gsmSerial.print(shortMessage);
    delay(500);
    gsmSerial.write(26); // Ctrl+Z
    
    // DÜZELTME: Çok kısa delivery timeout - 15 saniye
    String finalResponse = "";
    unsigned long deliveryTimeout = millis() + 15000;
    bool success = false;
    
    while (millis() < deliveryTimeout) {
        if (gsmSerial.available()) {
            char c = gsmSerial.read();
            finalResponse += c;
            
            if (finalResponse.indexOf("+CMGS:") >= 0) {
                success = true;
                Serial.println("\n✅ SMS SENT SUCCESSFULLY!");
                break;
            }
            
            if (finalResponse.indexOf("ERROR") >= 0) {
                Serial.println("\n❌ SMS ERROR: " + finalResponse);
                break;
            }
        }
        delay(100);
    }
    
    if (success) {
        Serial.println("📱 SMS delivered successfully!");
        lastSMSAlertTime = millis();
    } else {
        Serial.println("❌ SMS FAILED - Response: " + finalResponse);
        
        // DÜZELTME: Detaylı hata analizi
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
    
    // Cleanup
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
            
            // Sadece önemli UDP komutlarını log et
            bool shouldLog = command.startsWith("ALARM") || command == "PING_TEST" ||
                           command == "GET_GSM_STATUS" || command == "SILENCE_ALARM" ||
                           command == "REQUEST_SMS";
            
            if (shouldLog) {
                Serial.printf("UDP Command: %s\n", command.c_str());
            }
            
            String response = "";
            
            // Process UDP commands

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
            // DÜZELTME: UDP üzerinden silence alarm
            else if (command == "SILENCE_ALARM") {
                Serial.println("SILENCE_ALARM command received via UDP");
                stopBuzzer();
                response = "ALARM_SILENCED";
                Serial.println("Alarm silenced via UDP - response sent");
            }
            // DÜZELTME: UDP üzerinden SMS request
            else if (command == "REQUEST_SMS") {
                Serial.println("SMS requested via UDP - forcing send");
                if (checkGSMStatus()) {
                    String smsMsg = "SMART TRACKER STATUS: Your item is safe. ";
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
            
            // Send response
            udp.beginPacket(udp.remoteIP(), udp.remotePort());
            udp.print(response);
            udp.endPacket();
        }
    }
}

void printDetailedStatus() {
    Serial.println("\n=== SYSTEM STATUS (5 min interval) ===");
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
    if (alarmEnabled) {
        Serial.printf(" | Threshold: %d dBm", rssiThreshold);
    }
    Serial.println();
    Serial.printf("Uptime: %lu minutes | Free heap: %d bytes\n", 
                 millis() / 60000, ESP.getFreeHeap());
    Serial.println("=====================================\n");
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