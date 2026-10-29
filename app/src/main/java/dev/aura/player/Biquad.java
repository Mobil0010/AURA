package dev.aura.player;
/** RBJ Audio EQ Cookbook coefficients; the same transfer function drives audio and graph. */
public final class Biquad {
    public static double[] coefficients(AudioSettings.Band b,int rate){double f=Math.max(10,Math.min(rate*.49,b.frequency())),A=Math.pow(10,b.gain()/40),w=2*Math.PI*f/rate,c=Math.cos(w),a=Math.sin(w)/(2*Math.max(.1,b.q())),z=2*Math.sqrt(A)*a;double b0,b1,b2,a0,a1,a2;
        switch(b.type()){
            case "low" -> {b0=A*((A+1)-(A-1)*c+z);b1=2*A*((A-1)-(A+1)*c);b2=A*((A+1)-(A-1)*c-z);a0=(A+1)+(A-1)*c+z;a1=-2*((A-1)+(A+1)*c);a2=(A+1)+(A-1)*c-z;}
            case "high" -> {b0=A*((A+1)+(A-1)*c+z);b1=-2*A*((A-1)+(A+1)*c);b2=A*((A+1)+(A-1)*c-z);a0=(A+1)-(A-1)*c+z;a1=2*((A-1)-(A+1)*c);a2=(A+1)-(A-1)*c-z;}
            case "highpass" -> {b0=(1+c)/2;b1=-(1+c);b2=b0;a0=1+a;a1=-2*c;a2=1-a;}
            case "lowpass" -> {b0=(1-c)/2;b1=1-c;b2=b0;a0=1+a;a1=-2*c;a2=1-a;}
            default -> {b0=1+a*A;b1=-2*c;b2=1-a*A;a0=1+a/A;a1=-2*c;a2=1-a/A;}
        }return new double[]{b0/a0,b1/a0,b2/a0,a1/a0,a2/a0};}
    public static double response(double[] b,double f,int rate){double w=2*Math.PI*f/rate,c=Math.cos(w),s=Math.sin(w),c2=Math.cos(2*w),s2=Math.sin(2*w);return 10*Math.log10(((b[0]+b[1]*c+b[2]*c2)*(b[0]+b[1]*c+b[2]*c2)+(b[1]*s+b[2]*s2)*(b[1]*s+b[2]*s2))/((1+b[3]*c+b[4]*c2)*(1+b[3]*c+b[4]*c2)+(b[3]*s+b[4]*s2)*(b[3]*s+b[4]*s2)));}
    private final double[][] state;private double[] coeff={1,0,0,0,0};
    public Biquad(int channels){state=new double[channels][2];}
    public double sample(double input,int channel,double[] target){if(channel==0)for(int i=0;i<5;i++)coeff[i]+=(target[i]-coeff[i])*.002;double out=coeff[0]*input+state[channel][0];state[channel][0]=coeff[1]*input-coeff[3]*out+state[channel][1];state[channel][1]=coeff[2]*input-coeff[4]*out;return out;}
}
