package dev.aura.player;
import android.content.*;
import android.hardware.usb.*;

public final class UsbRoute {
    private static UsbDeviceConnection connection;private static UsbDevice selected;private static long generation;private static int deviceId=-1;public static String identity="USB";
    public static synchronized void select(Context c,UsbDevice device){UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);if(!manager.hasPermission(device))throw new IllegalStateException("USB 권한이 필요합니다");UsbDeviceConnection opened=manager.openDevice(device);if(opened==null)throw new IllegalStateException("USB DAC를 열 수 없습니다");close();connection=opened;deviceId=device.getDeviceId();identity=device.getVendorId()+":"+device.getProductId();selected=device;UsbVolume.selected(c);}
    public static synchronized boolean available(Context c){if(connection==null)return false;UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);for(UsbDevice d:manager.getDeviceList().values())if(d.getDeviceId()==deviceId&&manager.hasPermission(d))return connection.getFileDescriptor()>=0;close();return false;}
    public static synchronized int selectedId(){return deviceId;}
    public static synchronized int fd(){return connection==null?-1:connection.getFileDescriptor();}
    static synchronized long generation(){return generation;}
    static synchronized String volumeIdentity(long expected)throws java.io.IOException{check(expected);if(selected==null)return "";String serial="";try{serial=selected.getSerialNumber();}catch(SecurityException unavailable){}return selected.getVendorId()+":"+selected.getProductId()+":"+selected.getVersion()+":"+(serial==null||serial.isEmpty()?"model:"+selected.getProductName():"serial:"+serial);}
    static synchronized byte[] descriptors(long expected)throws java.io.IOException{check(expected);return connection.getRawDescriptors();}
    static synchronized void claimControl(long expected,int iface)throws java.io.IOException{check(expected);for(int i=0;selected!=null&&i<selected.getInterfaceCount();i++)if(selected.getInterface(i).getId()==iface){return;}}
    static synchronized int control(long expected,int type,int request,int value,int index,byte[] data)throws java.io.IOException{check(expected);return connection.controlTransfer(type,request,value,index,data,data.length,150);}
    private static void check(long expected)throws java.io.IOException{if(expected!=generation||connection==null)throw new java.io.IOException("USB DAC 연결이 변경되었습니다.");}
    public static synchronized void close(){generation++;selected=null;if(connection!=null){connection.close();connection=null;}deviceId=-1;UsbVolume.disconnected();}
}
