package org.nfcresearch.qpolllab;

import java.util.*;
import java.io.*;

public final class ImageRasterTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(Runnable r){try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected rejection");}
    private static void ppm(File directory,String name,int[] pixels,int width,int height)throws Exception{
        try(OutputStream out=new BufferedOutputStream(new FileOutputStream(new File(directory,name+".ppm")))){out.write(("P6\n"+width+" "+height+"\n255\n").getBytes("US-ASCII"));for(int p:pixels){out.write((p>>>16)&255);out.write((p>>>8)&255);out.write(p&255);}}
    }
    public static void main(String[] args)throws Exception {
        // Hand-written factory scan bytes: left column top->bottom 0,1,2,3;
        // right column 3,2,1,0. Right column is emitted first, bottom pixel first.
        int[] known=ImageRaster.preview(new byte[]{0x1b,(byte)0xe4},2,4);
        int[] order={0,3,1,2,2,1,3,0};for(int i=0;i<order.length;i++)check(known[i]==ImageRaster.color(order[i]),"Factory preview orientation / color mapping");
        Random random=new Random(72027);int[] panel=new int[552*768];
        for(int i=0;i<panel.length;i++)panel[i]=0xff000000|random.nextInt(1<<24);
        int[] original=panel.clone();
        for(boolean binary:new boolean[]{false,true})for(boolean noise:new boolean[]{false,true})for(int block:new int[]{1,2,4,8,16}) {
            byte[] codes=ImageRaster.quantize(panel,552,768,binary,128,noise,block);
            byte[] packed=PanelProtocol.packCodes(codes,552,768);check(packed.length==105984,"Payload size");
            int[] preview=ImageRaster.preview(packed,552,768);
            for(int i=0;i<codes.length;i++) {
                check((codes[i]&255)<(binary?2:4),"Invalid palette code");
                check(preview[i]==ImageRaster.color(codes[i]),"Preview differs from transmitted codes");
                int p=preview[i];check((p>>>24)==255,"Transparent preview");
                check((p&255)==0||p==0xffffffff,"Blue channel outside white");
            }
        }
        check(Arrays.equals(panel,original),"Source mutated");
        int[] gradient=new int[256*4];for(int y=0;y<4;y++)for(int x=0;x<256;x++)gradient[y*256+x]=0xff000000|(x<<16)|(x<<8)|x;
        for(int threshold:new int[]{0,64,128,192,255}) {
            byte[] codes=ImageRaster.quantize(gradient,256,4,true,threshold,false,1);
            for(int y=0;y<4;y++)for(int x=0;x<256;x++)check(codes[y*256+x]==(x>=threshold?1:0),"Threshold boundary");
        }
        int[] average={0xff646464,0xff969696,0xff646464,0xff969696};
        for(byte code:ImageRaster.quantize(average,2,2,true,120,true,2))check(code==1,"Block must threshold average");
        for(byte code:ImageRaster.quantize(average,2,2,true,130,true,2))check(code==0,"Block threshold ignored");
        int[] colored={0xffff0000,0xff00ff00,0xff0000ff,0xffffffff};
        check(Arrays.equals(ImageRaster.quantize(colored,4,1,true,128,false,1),new byte[]{0,1,0,1}),"Luminance channel weights");
        rejected(()->ImageRaster.quantize(new int[4],2,2,true,256,false,1));
        rejected(()->ImageRaster.quantize(new int[4],2,2,false,128,false,3));
        rejected(()->ImageRaster.preview(new byte[1],2,4));
        // Export actual quantizer/decoder output, including blue/cyan inputs.
        int w=552,h=768;int[] fixture=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int r=x*255/(w-1),g=y*255/(h-1),b=(x+y)%256;fixture[y*w+x]=0xff000000|(r<<16)|(g<<8)|b;}
        File out=new File(args[0]);ppm(out,"raster-source",fixture,w,h);
        for(boolean binary:new boolean[]{false,true})for(boolean noise:new boolean[]{false,true}){
            byte[] codes=ImageRaster.quantize(fixture,w,h,binary,128,noise,1);
            ppm(out,"raster-"+(binary?"binary":"color")+(noise?"-dither":"-plain"),ImageRaster.preview(PanelProtocol.packCodes(codes,w,h),w,h),w,h);
        }
        System.out.println("ImageRasterTest: PASS (40 full-panel modes, exact packed-preview equality, no blue palette, binary thresholds, averaging, fixture export)");
    }
}
