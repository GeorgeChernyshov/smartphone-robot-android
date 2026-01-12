package jp.oist.abcvlib.util;

import static java.lang.Thread.sleep;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import jp.oist.abcvlib.util.Logger;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


public class UsbSerial implements SerialInputOutputManager.Listener{

    private static final ReentrantLock lock = new ReentrantLock();
    protected static final Condition packetReceived = lock.newCondition();

    private final Context context;
    private final UsbManager usbManager;
    private UsbSerialPort port;
    protected SerialReadyListener serialReadyListener;
    private int cnt = 0;
    private float[] pwm = new float[]{1.0f, 0.5f, 0.0f, -0.5f, -1.0f};
    private byte[] responseData;
    private final int RP2020_PACKET_SIZE_STATE = 64;
    // 1024 + 3 for start, command, and stop markers sent as putchar. (i.e. they won't be optimized anyway).
    private final int RP2020_PACKET_SIZE_LOG = 1027;
    // Initialize as the smallest of the two packet sizes
    private int packetDataSize = RP2020_PACKET_SIZE_STATE;
    private AndroidToRP2040Command packetType = AndroidToRP2040Command.NACK;
    protected final CircularFifoQueue<FifoQueuePair> fifoQueue = new CircularFifoQueue<>(256);
    int timeout = 1000; //1s
    int totalBytesRead = 0; // Track total bytes read
    final ByteBuffer packetBuffer = ByteBuffer.allocate((512 * 128)+ 8);// Adjust the buffer size as needed
    boolean packetFound = false;
    //Ensure a proper start and stop mark present before adding anything to the fifoQueue
    StartStopIndex startStopIdx;
    // Used to signal when a new packet is available between thread handling sending and receiving
    String TAG = "UsbSerial";

    private class StartStopIndex{
        private int startIdx;
        private int stopIdx;
        private StartStopIndex(int startIdx, int stopIdx){
            this.startIdx = startIdx;
            this.stopIdx = stopIdx;
        }
    }

    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";

    public UsbSerial(Context context,
                     UsbManager usbManager,
                     SerialReadyListener serialReadyListener) throws IOException {
        this.context = context;
        this.serialReadyListener = serialReadyListener;
        // Find all available drivers from attached devices.
        this.usbManager = usbManager;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        startStopIdx = new StartStopIndex(-1, -1);
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);

        if (deviceList.isEmpty()){
            throw new IOException("No USB devices found");
        }

        for (UsbDevice d: deviceList.values()){
            if (d.getManufacturerName().equals("Seeed") && d.getProductName().equals("Seeeduino XIAO")){
                Logger.i(Thread.currentThread().getName(), "Found a XIAO. Connecting...");
                connect(d);
            }
            else if (d.getManufacturerName().equals("Raspberry Pi") && d.getProductName().equals("Pico Test Device")){
                Logger.i(Thread.currentThread().getName(), "Found a Pico Test Device. Connecting...");
                connect(d);
            }
            else if (d.getManufacturerName().equals("Raspberry Pi") && d.getProductName().equals("Pico")){
                Logger.i(Thread.currentThread().getName(), "Found a Pi. Connecting...");
                connect(d);
            }
        }

        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        BroadcastReceiver usbReceiver = new MyBroadcastReceiver();
        context.registerReceiver(usbReceiver, filter);
    }

    private void connect(UsbDevice device) throws IOException {
        if(usbManager.hasPermission(device)){
            Logger.i(Thread.currentThread().getName(), "Has permission to connect to device");
            UsbDeviceConnection connection = usbManager.openDevice(device);
            openPort(connection);
        }else{
            Logger.i(Thread.currentThread().getName(), "Requesting permission to connect to device");
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );

            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private void openPort(UsbDeviceConnection connection) {
        Logger.i(Thread.currentThread().getName(), "Opening port");
        UsbSerialDriver driver = getDriver();
        UsbSerialPort port = driver.getPorts().get(0); // Most devices have just one port (port 0)
        try {
            port.open(connection);
            port.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
            port.setDTR(true);
            this.port = port;
            assert port != null;
            SerialInputOutputManager usbIoManager = new SerialInputOutputManager(port, this);
            // Adding this as there doesn't appear to be any call back in the usbIoManager that
            // will call onSerialReady after initialization. As it stands, there were things occurring
            // in onSerialReady that were being executed before the usbIoManager was initialized.
            sleep(100);
            usbIoManager.start();
            serialReadyListener.onSerialReady(this);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private UsbSerialDriver getDriver(){
        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x2886, 0x802F, CdcAcmSerialDriver.class); // Seeeduino XIAO
        customTable.addProduct(11914, 10, CdcAcmSerialDriver.class); // Raspberry Pi Pico
        customTable.addProduct(0x0000, 0x0001, CdcAcmSerialDriver.class); // Custom Raspberry Pi Pico
        UsbSerialProber prober = new UsbSerialProber(customTable);
        List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(usbManager);
        if (availableDrivers.isEmpty()) {
            ErrorHandler.eLog("Serial", "No USB Serial drivers found", new Exception(), true);
        }
        return availableDrivers.get(0);
    }

    @Override
    public void onNewData(byte[] data) {

        //TODO I feel this should be executed in a separate thread otherwise the
        // SerialInputOutputManager thread may be delayed and miss data

        // print the byte[] as an array of hex values
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        Logger.d(TAG, "onNewData Received: " + sb.toString());

        // Run the packet verification in a separate thread
        try {
            lock.lock();
            if (verifyPacket(data)){
                Logger.d(TAG, "Packet verified");
                Logger.d(TAG, "packetReceived.signal()");
                packetReceived.signal();
            }
            else{
                Logger.d(TAG, "Incomplete Packet. Waiting for more data");
            };
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    protected void send(byte[] packet, int timeout) throws IOException {
        port.write(packet, timeout);
        Logger.i(Thread.currentThread().getName(), "send()");
    }

    /**
     * Blocks until a response is received
     */
    protected int awaitPacketReceived(int timeout) {
        int returnVal = -1;
        // Wait until packet is available
        lock.lock();
        try{
            if (!packetReceived.await(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)){
//                throw new RuntimeException("SerialTimeoutException on send. The serial connection " +
//                        "with the rp2040 is not working as expected and timed out");
            } else {
                Logger.d(Thread.currentThread().getName(), "packetReceived.await() completed. Packet received from rp2040");
                returnVal = 1;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
        return returnVal;
    }

    private StartStopIndex startStopIndexSearch(ByteBuffer data) throws IOException {
        StartStopIndex startStopIdx = new StartStopIndex(-1, -1);

        int i = 0;
        data.flip();
        while (i < data.limit()){
            // get value at position and increment position by 1 (so don't call it multiple times)
            byte value = data.get();

            // Always overwrite the value of stopIdx as you want the last instance
            if (value == AndroidToRP2040Command.STOP.getHexValue()) {
                startStopIdx.stopIdx = i;
                Logger.v("serial", "stopIdx found at " + i);
            }
            // Only write the value of startIdx once as you only want the first instance.
            else if (value == AndroidToRP2040Command.START.getHexValue() && startStopIdx.startIdx < 0){
                startStopIdx.startIdx = i;
                Logger.v("serial", "startIdx found at " + i);
            }
            i++;
        }
        // flip will set limit to position and position to 0
        // need to set limit back to capacity after reading
        data.limit(data.capacity());

        return startStopIdx;
    }

    /**
     * synchronized as newData might be called again while this method is running
     * @param bytes to be verified to contain a valid packet. If so, the packet is added to the fifoQueue
     * @return 0 if successfully found a complete packet or found an erroneous packet,
     * -1 if not enough data to determine
     * @throws IOException if fifoQueue is full
     */
    protected synchronized boolean verifyPacket(byte[] bytes) throws IOException {
        if (packetBuffer.remaining() < bytes.length) {
            Logger.e("verifyPacket", "Buffer overflow risk: clearing buffer and resynchronizing.");
            packetBuffer.clear();
            onBadPacket();
            return false;
        }
        packetBuffer.put(bytes);

        // If startIdx not yet found, find it
        if (startStopIdx.startIdx < 0){
            startStopIdx = startStopIndexSearch(packetBuffer);
        }

        // If startIdx still not found, return false
        if (startStopIdx.startIdx < 0){
            Logger.v("serial", "StartIdx not yet found. Waiting for more data");
            return false;
        }
        // else startIdx found
        else{
            Logger.v("serial", "StartIdx found at " + startStopIdx.startIdx);
            // if packetType not yet found, find it
            if (packetType == AndroidToRP2040Command.NACK){
                // If position is 0, nothing received yet. If position is 1 only the start mark has been received.
                // position 2 is the packetType, and positions 3 and 4 are a short for packetSize
                    if (packetBuffer.position() >= RP2040ToAndroidPacket.Offsets.DATA){
                    try{
                        packetType = AndroidToRP2040Command.getEnumByValue(packetBuffer.get(startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.PACKET_TYPE));
                    }catch (IndexOutOfBoundsException e){
                        Logger.e("serial", "IndexOutOfBoundsException: " + e.getMessage());
                        Logger.e("serial", "Unknown packetType: " + packetType);
                        // Ignore this packet as it is corrupted by some other data being sent between.
                        packetBuffer.clear();
                        return true;
                    }
                    // get the packetDataSize. It is stored at index 3 and 4 as a short
                    packetDataSize = packetBuffer.getShort(startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.DATA_SIZE) & 0xFFFF;
                    if (packetDataSize > 2048) { // sanity check for max packet size
                        Logger.e("verifyPacket", "Unreasonable packet size: " + packetDataSize + ". Resynchronizing.");
                        onBadPacket();
                        return true;
                    }
                    Logger.v("serial", packetType + " packetType of size " + packetDataSize + " found at " + (startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.PACKET_TYPE));
                }
                else{
                    Logger.v("serial", "Nothing other than startIdx found. Waiting for more data");
                    return false;
                }
            }

            // Check if you have enough data for a full packet before processing anything
            // +1 for moving 1 past packetBuffer.position() to endIdx byte
            startStopIdx.stopIdx = startStopIdx.startIdx + packetDataSize + (RP2040ToAndroidPacket.Offsets.DATA);
            // +1 for packetBuffer.position() needing to be 1 after endIdx byte to indicate that you have the stopIdx byte
            if (packetBuffer.position() < (startStopIdx.stopIdx + 1)){
                Logger.d("verifyPacket", "Data received not yet enough to fill " +
                        packetType + " packetType. Waiting for more data");
                Logger.d("verifyPacket", packetBuffer.position() + " bytes recvd thus far. Require " + startStopIdx.stopIdx);
                return false;
            }
            // You have enough data for a full packet
            else{
                if (packetBuffer.get(startStopIdx.stopIdx) != AndroidToRP2040Command.STOP.getHexValue()){
                    onBadPacket();
                    return true;
                }
                else {
                    if (packetType == AndroidToRP2040Command.GET_LOG) {
                        Logger.i("verifyPacket", "GET_LOG command received");
                    } else if (packetType == AndroidToRP2040Command.SET_MOTOR_LEVELS) {
                        Logger.i("verifyPacket", "SET_MOTOR_LEVELS command received");
                    } else if (packetType == AndroidToRP2040Command.GET_STATE) {
                        Logger.i("verifyPacket", "GET_STATE command received");
                    } else {
                        Logger.e("verifyPacket", "Unknown packetType: " + packetType);
                        onBadPacket();
                        return true;
                    }
                    onCompletePacketReceived();
                    return true;
                }
            }
        }
    }

    private void onCompletePacketReceived(){
        packetBuffer.position(startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.DATA);
        packetBuffer.limit(startStopIdx.stopIdx);
        byte[] partialArray = new byte[packetBuffer.remaining()];
        packetBuffer.get(partialArray);
        packetBuffer.clear();
        synchronized (fifoQueue) {
            if (fifoQueue.isAtFullCapacity()) {
                Logger.e("serial", "fifoQueue is full");
                throw new RuntimeException("fifoQueue is full");
            } else {
                // Log partialArray as array of hex values
                StringBuilder sb = new StringBuilder();
                for (byte b : partialArray) {
                    sb.append(String.format("%02X ", b));
                }
                Logger.d("verifyPacket", "Adding Packet: " + sb.toString() + " to fifoQueue");
                FifoQueuePair fifoQueuePair = new FifoQueuePair(packetType, partialArray);
                fifoQueue.add(fifoQueuePair); // Add the partialArray to the queue
            }
        }
        // reset to default value;
        packetType = AndroidToRP2040Command.NACK;
        badPacketCount = 0; // Reset on successful packet
    }

    private int badPacketCount = 0;
    private static final int BAD_PACKET_THRESHOLD = 5;

    private void onBadPacket(){
        Logger.e("serial", "Bad packet received. Clearing buffer and sending next command.");
        // Ignore this packet as it is corrupted by some other data being sent between.
        packetBuffer.clear();
        while (packetBuffer.hasRemaining()) {
            packetBuffer.put((byte) 0);
        }
        resyncToNextStartMarker();
        // reset to default value;
        packetType = AndroidToRP2040Command.NACK;
        badPacketCount++;
        if (badPacketCount >= BAD_PACKET_THRESHOLD) {
            Logger.e("serial", "Too many consecutive bad packets. Consider resetting connection or notifying user.");
            // Optionally: trigger recovery, e.g., re-initialize serial connection or notify user
            // recoverSerialConnection();
            // For now, just reset the counter
            badPacketCount = 0;
        }
    }

    private void resyncToNextStartMarker() {
        packetBuffer.flip();
        int startIdx = -1;
        for (int i = 0; i < packetBuffer.limit(); i++) {
            if (packetBuffer.get(i) == AndroidToRP2040Command.START.getHexValue()) {
                startIdx = i;
                break;
            }
        }
        if (startIdx >= 0) {
            packetBuffer.position(startIdx);
            packetBuffer.compact();
        } else {
            packetBuffer.clear();
        }
    }

    @Override
    public void onRunError(Exception e) {
        Logger.e("serial", "error: " + e.getLocalizedMessage());
        e.printStackTrace();
    }

    private class MyBroadcastReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        try {
                            connect(device);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }else {
                Logger.d("serial", "permission denied for device " + device);
            }
        }
    }
}
