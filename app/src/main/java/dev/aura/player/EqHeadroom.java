package dev.aura.player;

import java.util.*;

/** Peak of the cascaded filters, cached by actual coefficients' settings and output rate. */
final class EqHeadroom {
    private record Key(List<AudioSettings.Band> bands,int rate) {}
    private static final Map<Key,Double> cache=new LinkedHashMap<>(8,.75f,true);
    static synchronized double peakDb(List<AudioSettings.Band> bands,int rate){Key key=new Key(bands,rate);Double hit=cache.get(key);if(hit!=null)return hit;double[][] co=new double[bands.size()][];for(int i=0;i<co.length;i++)co[i]=Biquad.coefficients(bands.get(i),rate);double nyquist=rate*.5;double[] points=new double[1030+bands.size()];int n=0;for(int i=0;i<=768;i++)points[n++]=nyquist*i/768;for(int i=0;i<=256;i++)points[n++]=Math.min(nyquist,10*Math.pow(nyquist/10,i/256.));for(var b:bands)points[n++]=Math.max(10,Math.min(rate*.49,b.frequency()));Arrays.sort(points,0,n);double[] values=new double[n];double peak=1;for(int i=0;i<n;i++){values[i]=magnitude(co,points[i],rate);peak=Math.max(peak,values[i]);}for(int i=1;i<n-1;i++)if(values[i]>=values[i-1]&&values[i]>=values[i+1]&&values[i]>1.000001){double lo=points[i-1],hi=points[i+1];for(int step=0;step<24;step++){double a=lo+(hi-lo)/3,b=hi-(hi-lo)/3;double av=magnitude(co,a,rate),bv=magnitude(co,b,rate);peak=Math.max(peak,Math.max(av,bv));if(av<bv)lo=a;else hi=b;}}double result=10*Math.log10(peak);cache.put(key,result);if(cache.size()>8)cache.remove(cache.keySet().iterator().next());return result;}
    private static double magnitude(double[][] sections,double f,int rate){double x=Math.cos(2*Math.PI*f/rate),x2=2*x*x-1,s2=Math.max(0,1-x*x),product=1;for(double[] b:sections){double nr=b[0]+b[1]*x+b[2]*x2,ni=b[1]+2*b[2]*x,dr=1+b[3]*x+b[4]*x2,di=b[3]+2*b[4]*x;product*=(nr*nr+ni*ni*s2)/Math.max(1e-30,dr*dr+di*di*s2);}return product;}
    static double appliedPreamp(AudioSettings.Config c,int rate){if(c.perfect()||!c.eq()||!c.autoEqPreamp())return c.preamp();double peak=peakDb(c.bands(),rate);return Math.min(c.preamp(),-peak-(peak>.01?1:0));}
    static double compensationDb(AudioSettings.Config c,int rate){return appliedPreamp(c,rate)-c.preamp();}
}
