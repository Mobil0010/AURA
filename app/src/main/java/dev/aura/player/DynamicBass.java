package dev.aura.player;
/** Low-shelf reinforcement with linked, fast attack/slow release peak control.
 * No Sony code or algorithm. Per-frame state is allocated once, never in the audio loop.
 */
final class DynamicBass {
 private final Biquad bass;private double[] coefficients;private double amount=-1,frequency=-1,attenuation=1,wet;
 DynamicBass(int channels){bass=new Biquad(channels);}
 void configure(double amount,double frequency,int rate){if(amount!=this.amount||frequency!=this.frequency){this.amount=amount;this.frequency=frequency;coefficients=Biquad.coefficients(new AudioSettings.Band(frequency,amount*0.12,.707,"low"),rate);}}
 boolean active(){return wet>.00001||attenuation<.9999;}
 void frame(double[] samples,int channels){double target=amount>0?1:0;wet+=(target-wet)*.002;double peak=0;for(int ch=0;ch<channels;ch++){double raw=samples[ch],boosted=bass.sample(raw,ch,coefficients);samples[ch]=raw+(boosted-raw)*wet;peak=Math.max(peak,Math.abs(samples[ch]));}double desired=peak>.97?.97/peak:1;if(desired<attenuation)attenuation=desired;else attenuation+=(desired-attenuation)*.0002;for(int ch=0;ch<channels;ch++)samples[ch]*=attenuation;}
}
