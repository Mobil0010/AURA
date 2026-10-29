#include <jni.h>
#include <unistd.h>
#include <libusb.h>
#include <vector>
#include <deque>
#include <map>
#include <mutex>
#include <thread>
#include <atomic>
#include <cmath>
#include <string>
#include <chrono>
#include <algorithm>
#include "usb_clock.h"
#include "pcm_ring.h"
#include "pcm_padding.h"
#include <set>
struct UsbAudio {
    int ownedFd=-1;libusb_context* context=nullptr;libusb_device_handle* device=nullptr;int iface=-1,controlIface=-1;bool streamClaimed=false,controlClaimed=false;int rate=0,channels=0,bytes=0,sourceBytes=0,validBits=0,maxPacket=0,packetCount=8;
    double interval=0.001,fraction=0;std::atomic<double> feedback{0};unsigned char endpoint=0;
    std::atomic<bool> running{false},paused{true};std::atomic<int> active{0};std::atomic<long long> frames{0},underruns{0};
    std::map<libusb_transfer*,long long> mediaFrames;std::mutex mutex;aura::PcmRing ring;std::vector<unsigned char> writeScratch;size_t capacity=0;std::vector<libusb_transfer*> transfers;std::thread events;
};
static void failure(JNIEnv* e,const std::string& msg){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),msg.c_str());}
static int read24(const unsigned char* p){return p[0]|p[1]<<8|p[2]<<16;}
static void prepare(UsbAudio* s,libusb_transfer* t){std::lock_guard<std::mutex> lock(s->mutex);size_t at=0;long long media=0;for(int i=0;i<t->num_iso_packets;i++){double measured=s->feedback.load();double f=measured>0?measured:s->rate*s->interval;s->fraction+=f;int frames=(int)s->fraction;s->fraction-=frames;int size=std::min(s->maxPacket,frames*s->channels*s->bytes);size-=size%(s->channels*s->bytes);t->iso_packet_desc[i].length=size;bool consume=!s->paused;size_t got=consume?std::min((size_t)size,s->ring.size()):0;got-=got%(s->channels*s->bytes);s->ring.read(t->buffer+at,got);std::fill(t->buffer+at+got,t->buffer+at+size,0);media+=got/(s->channels*s->bytes);if(consume&&got<(size_t)size)s->underruns++;at+=size;}s->mediaFrames[t]=media;t->length=at;}
static void LIBUSB_CALL audioCallback(libusb_transfer* t){auto* s=(UsbAudio*)t->user_data;if(t->status==LIBUSB_TRANSFER_COMPLETED&&!s->paused){std::lock_guard<std::mutex> lock(s->mutex);s->frames+=s->mediaFrames[t];}if(t->status==LIBUSB_TRANSFER_NO_DEVICE)s->running=false;if(s->running&&t->status!=LIBUSB_TRANSFER_NO_DEVICE){prepare(s,t);if(libusb_submit_transfer(t)==0)return;}s->active--;}
static void LIBUSB_CALL feedbackCallback(libusb_transfer* t){auto* s=(UsbAudio*)t->user_data;if(t->status==LIBUSB_TRANSFER_COMPLETED){int len=t->iso_packet_desc[0].actual_length;auto* b=t->buffer;if(len==3||len==4){double value=len==3?read24(b)/16384.:(read24(b)+(double)b[3]*16777216.)/65536.;double frameTime=len==3?.001:.000125;double wanted=value*s->interval/frameTime;double nominal=s->rate*s->interval;if(wanted>nominal*.9&&wanted<nominal*1.1)s->feedback=wanted;}}if(s->running&&t->status!=LIBUSB_TRANSFER_NO_DEVICE&&libusb_submit_transfer(t)==0)return;s->active--;}
static void closeUsb(UsbAudio* s){if(!s)return;s->running=false;for(auto* t:s->transfers)libusb_cancel_transfer(t);if(s->events.joinable())s->events.join();for(auto* t:s->transfers){delete[] t->buffer;libusb_free_transfer(t);}if(s->device){if(s->streamClaimed){libusb_set_interface_alt_setting(s->device,s->iface,0);libusb_release_interface(s->device,s->iface);}if(s->controlClaimed)libusb_release_interface(s->device,s->controlIface);libusb_close(s->device);}if(s->context)libusb_exit(s->context);if(s->ownedFd>=0)close(s->ownedFd);delete s;}
extern "C" JNIEXPORT jlong JNICALL Java_dev_aura_player_NativeAudio_openUsb(JNIEnv* env,jclass,jint fd,jint rate,jint channels,jint bits,jint bufferMs){
    auto* s=new UsbAudio();s->rate=rate;s->channels=channels;s->bytes=bits/8;s->sourceBytes=bits/8;s->validBits=bits;s->capacity=(size_t)rate*channels*s->bytes*std::max(50,bufferMs)/1000;
    if(rate<8000||rate>1536000||channels<1||channels>8||(bits!=16&&bits!=24&&bits!=32)||bufferMs<1||bufferMs>2000||s->capacity>16000000){delete s;failure(env,"Unsupported USB output resource configuration");return 0;}
    s->ownedFd=dup(fd);if(s->ownedFd<0){closeUsb(s);failure(env,"Invalid USB permission file descriptor");return 0;}
    libusb_set_option(nullptr,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);int error=libusb_init(&s->context);if(!error)error=libusb_wrap_sys_device(s->context,s->ownedFd,&s->device);if(error){closeUsb(s);failure(env,std::string("USB permission/wrap: ")+libusb_error_name(error));return 0;}
    libusb_config_descriptor* config=nullptr;if(libusb_get_active_config_descriptor(libusb_get_device(s->device),&config)<0){closeUsb(s);failure(env,"Cannot read USB configuration");return 0;}
    int alt=-1,ctrl=0,clock=0,protocol=0,clockControls=0;unsigned char fbEp=0;int fbSize=0;
    std::map<std::pair<int,int>,int> clocks,controls;std::map<std::pair<int,int>,std::vector<int>> selectors;std::set<int> controlInterfaces;
    for(int i=0;i<config->bNumInterfaces;i++)for(int j=0;j<config->interface[i].num_altsetting;j++){
        const auto& a=config->interface[i].altsetting[j];if(a.bInterfaceClass!=1||a.bInterfaceSubClass!=1)continue;controlInterfaces.insert(a.bInterfaceNumber);
        for(int p=0;p+3<=a.extra_length;){int len=a.extra[p];if(len<3||p+len>a.extra_length)break;const auto* d=a.extra+p;
            if(d[1]==0x24&&a.bInterfaceProtocol==0x20&&len>=4){auto key=std::make_pair(int(a.bInterfaceNumber),int(d[3]));if(d[2]==2&&len>=17)clocks[key]=d[7];if(d[2]==0x0a&&len>=8)controls[key]=d[5];if(d[2]==0x0b&&len>=7&&len>=7+d[4])selectors[key]=std::vector<int>(d+5,d+5+d[4]);}p+=len;
        }
    }
    for(int pass=0;pass<2&&alt<0;pass++)for(int i=0;i<config->bNumInterfaces&&alt<0;i++)for(int j=0;j<config->interface[i].num_altsetting&&alt<0;j++){
        const auto& a=config->interface[i].altsetting[j];if(a.bInterfaceClass!=1||a.bInterfaceSubClass!=2||a.bAlternateSetting==0)continue;
        int ch=0,width=0,resolution=0,terminal=0;bool pcm=false,rateOk=a.bInterfaceProtocol==0x20;
        for(int p=0;p+3<=a.extra_length;){int len=a.extra[p];if(len<3||p+len>a.extra_length)break;auto* d=a.extra+p;
            if(d[1]==0x24&&d[2]==1){terminal=len>3?d[3]:0;if(a.bInterfaceProtocol==0x20&&len>=11){pcm=(d[6]&1)!=0;ch=d[10];}else if(len>=7)pcm=(d[5]|d[6]<<8)==1;}
            if(d[1]==0x24&&d[2]==2&&len>=6&&d[3]==1){if(a.bInterfaceProtocol==0x20){width=d[4];resolution=d[5];}else if(len>=8){ch=d[4];width=d[5];resolution=d[6];int n=d[7];if(n==0&&len>=14)rateOk=rate>=read24(d+8)&&rate<=read24(d+11);else for(int k=0;k<n&&8+3*k+3<=len;k++)if(read24(d+8+3*k)==rate)rateOk=true;}}
            p+=len;
        }
        bool exact=width==s->sourceBytes&&resolution==bits;if(!pcm||ch!=channels||width<s->sourceBytes||width>4||resolution<bits||resolution>width*8||!rateOk||(pass==0&&!exact)||(pass==1&&exact))continue;
        const libusb_endpoint_descriptor* data=nullptr;const libusb_endpoint_descriptor* feedback=nullptr;
        for(int e=0;e<a.bNumEndpoints;e++){const auto& ep=a.endpoint[e];if((ep.bmAttributes&3)!=1)continue;if((ep.bEndpointAddress&0x80)==0)data=&ep;else if(((ep.bmAttributes>>4)&3)==1)feedback=&ep;}
        if(!data)continue;int speed=libusb_get_device_speed(libusb_get_device(s->device));s->interval=(speed>=LIBUSB_SPEED_HIGH?.000125:.001)*std::pow(2.,std::max(0,(int)data->bInterval-1));s->maxPacket=(data->wMaxPacketSize&0x7ff)*(1+((data->wMaxPacketSize>>11)&3));if(s->rate*s->interval*s->channels*width>s->maxPacket)continue;
        // Asynchronous sinks need an explicit feedback endpoint; implicit-feedback devices are rejected.
        if(((data->bmAttributes>>2)&3)==1&&!feedback)continue;
        s->bytes=width;ctrl=0;for(int control:controlInterfaces)if(control<a.bInterfaceNumber)ctrl=control;s->iface=a.bInterfaceNumber;alt=a.bAlternateSetting;s->endpoint=data->bEndpointAddress;protocol=a.bInterfaceProtocol;clock=clocks[{ctrl,terminal}];if(feedback){fbEp=feedback->bEndpointAddress;fbSize=feedback->wMaxPacketSize&0x7ff;}
    }
    libusb_free_config_descriptor(config);
    if(alt<0){closeUsb(s);failure(env,"No compatible UAC PCM interface (rate/channels/bit depth or feedback unsupported)");return 0;}
    s->capacity=(size_t)rate*channels*s->bytes*std::max(50,bufferMs)/1000;if(s->capacity>16000000){closeUsb(s);failure(env,"USB buffer limit exceeded");return 0;}try{s->ring.reset(s->capacity);}catch(const std::bad_alloc&){closeUsb(s);failure(env,"USB buffer allocation failed");return 0;}
    libusb_set_auto_detach_kernel_driver(s->device,1);
    std::string stage="claim streaming interface";
    error=libusb_claim_interface(s->device,s->iface);s->streamClaimed=error==0;
    if(!error){stage="stream idle alternate setting";error=libusb_set_interface_alt_setting(s->device,s->iface,0);}
    if(!error&&protocol==0x20){
        s->controlIface=ctrl;stage="claim AudioControl interface";error=libusb_claim_interface(s->device,ctrl);s->controlClaimed=error==0;
        std::set<int> seen;
        while(!error&&selectors.count({ctrl,clock})){
            if(!seen.insert(clock).second){stage="recursive clock selector";error=LIBUSB_ERROR_NOT_SUPPORTED;break;}
            const auto& pins=selectors[{ctrl,clock}];unsigned char pin=1;stage="clock selector GET_CUR";
            int n=libusb_control_transfer(s->device,0xa1,1,0x100,(clock<<8)|ctrl,&pin,1,1000);
            if(n!=1&&pins.size()!=1){error=n<0?n:LIBUSB_ERROR_IO;break;}
            if(pin<1||pin>pins.size()){error=LIBUSB_ERROR_NOT_SUPPORTED;break;}clock=pins[pin-1];
        }
        if(!error){auto control=controls.find({ctrl,clock});clockControls=control==controls.end()?0:control->second;if(clock==0||control==controls.end()){stage="unsupported clock topology";error=LIBUSB_ERROR_NOT_SUPPORTED;}
            else error=aura::configureClock(rate,clockControls,[&](bool read,unsigned char* bytes,int size){return libusb_control_transfer(s->device,read?0xa1:0x21,1,0x100,(clock<<8)|ctrl,bytes,size,1000);},[]{std::this_thread::sleep_for(std::chrono::milliseconds(30));},stage);
        }
    }
    if(!error&&protocol!=0x20&&controlInterfaces.count(ctrl)){s->controlIface=ctrl;s->controlClaimed=libusb_claim_interface(s->device,ctrl)==0;}
    if(!error){stage="activate streaming alternate setting";error=libusb_set_interface_alt_setting(s->device,s->iface,alt);}
    if(!error&&protocol!=0x20){stage="UAC1 endpoint sample rate";unsigned char r[3]={(unsigned char)rate,(unsigned char)(rate>>8),(unsigned char)(rate>>16)};int result=libusb_control_transfer(s->device,0x22,1,0x100,s->endpoint,r,3,1000);if(result!=3){unsigned char current[3]={};int n=libusb_control_transfer(s->device,0xa2,0x81,0x100,s->endpoint,current,3,1000);if(n!=3||read24(current)!=rate)error=result<0?result:LIBUSB_ERROR_IO;}}
    if(error){std::string detail="USB "+stage+": "+libusb_error_name(error)+" (AS="+std::to_string(s->iface)+", alt="+std::to_string(alt)+", AC="+std::to_string(ctrl)+", clock="+std::to_string(clock)+", "+std::to_string(rate)+" Hz / "+std::to_string(bits)+" bit)";closeUsb(s);failure(env,detail);return 0;}
    s->running=true;
    for(int i=0;i<8;i++){auto* t=libusb_alloc_transfer(s->packetCount);auto* b=new unsigned char[s->maxPacket*s->packetCount];libusb_fill_iso_transfer(t,s->device,s->endpoint,b,s->maxPacket*s->packetCount,s->packetCount,audioCallback,s,1000);prepare(s,t);s->transfers.push_back(t);if(libusb_submit_transfer(t)==0)s->active++;else{s->running=false;break;}}
    if(s->running&&fbEp&&fbSize>=3){auto* t=libusb_alloc_transfer(1);auto* b=new unsigned char[fbSize];libusb_fill_iso_transfer(t,s->device,fbEp,b,fbSize,1,feedbackCallback,s,1000);libusb_set_iso_packet_lengths(t,fbSize);s->transfers.push_back(t);if(libusb_submit_transfer(t)==0)s->active++;else s->running=false;}
    s->events=std::thread([s]{while(s->running||s->active>0){timeval timeout{0,20000};libusb_handle_events_timeout_completed(s->context,&timeout,nullptr);}});
    if(!s->running){closeUsb(s);failure(env,"Could not start USB isochronous transfers");return 0;}return reinterpret_cast<jlong>(s);
}
extern "C" JNIEXPORT jint JNICALL Java_dev_aura_player_NativeAudio_writeUsb(JNIEnv* env,jclass,jlong h,jbyteArray data,jint offset,jint size){auto* s=(UsbAudio*)h;if(!s||!s->running)return -1;if(offset<0||size<0||offset>env->GetArrayLength(data)-size)return -1;std::lock_guard<std::mutex> lock(s->mutex);size_t inputFrame=s->channels*s->sourceBytes,wireFrame=s->channels*s->bytes;size_t frames=std::min((size_t)size/inputFrame,(s->capacity-s->ring.size())/wireFrame),n=frames*inputFrame,wire=frames*wireFrame;if(!frames)return 0;if(s->writeScratch.size()<wire)try{s->writeScratch.resize(wire);}catch(const std::bad_alloc&){return LIBUSB_ERROR_NO_MEM;}env->GetByteArrayRegion(data,offset,n,(jbyte*)s->writeScratch.data());if(env->ExceptionCheck())return -1;aura::padPcm(s->writeScratch.data(),frames*s->channels,s->sourceBytes,s->bytes);s->ring.write(s->writeScratch.data(),wire);return n;}
extern "C" JNIEXPORT jlong JNICALL Java_dev_aura_player_NativeAudio_usbFrames(JNIEnv*,jclass,jlong h){return h?((UsbAudio*)h)->frames.load():0;}
extern "C" JNIEXPORT void JNICALL Java_dev_aura_player_NativeAudio_pauseUsb(JNIEnv*,jclass,jlong h,jboolean pause){if(h)((UsbAudio*)h)->paused=pause;}
extern "C" JNIEXPORT jstring JNICALL Java_dev_aura_player_NativeAudio_usbInfo(JNIEnv* env,jclass,jlong h){auto* s=(UsbAudio*)h;std::string info=s?"USB UAC PCM · "+std::to_string(s->rate)+" Hz / "+std::to_string(s->validBits)+" bit"+(s->sourceBytes!=s->bytes?" ("+std::to_string(s->bytes*8)+" bit slot, lossless padding)":"")+" · underruns "+std::to_string(s->underruns.load()):"USB disconnected";return env->NewStringUTF(info.c_str());}
extern "C" JNIEXPORT void JNICALL Java_dev_aura_player_NativeAudio_closeUsb(JNIEnv*,jclass,jlong h){closeUsb((UsbAudio*)h);}
