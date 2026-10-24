package org.nfcresearch.qpolllab;

import java.util.Arrays;

public final class ImageBlocksTest {
    private static final int[] P={0xff000000,0xffffffff,0xfff5cd00,0xffc80000};
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(Runnable r){try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected rejection");}
    public static void main(String[] args) {
        for(int block:new int[]{1,2,4,8,16})for(int color=0;color<4;color++) {
            int[] pixels=new int[19*21];Arrays.fill(pixels,P[color]);
            byte[] codes=ImageBlocks.quantize(pixels,19,21,block,P);
            for(byte code:codes)check(code==color,"Solid color changed / edge unfilled");
        }
        int[] checker=new int[16*16];
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)checker[y*16+x]=P[(x+y)%2];
        byte[] fine=ImageBlocks.quantize(checker,16,16,1,P);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)check(fine[y*16+x]==(x+y)%2,"Fine pattern changed");
        byte[] coarse=ImageBlocks.quantize(checker,16,16,8,P);
        for(byte code:coarse)check(code==coarse[0],"Checkerboard not consolidated");

        // Partial blocks must average only their own pixels, including the final corner.
        int[] edges={P[0],P[0],P[3],P[0],P[0],P[3],P[2],P[2],P[1]};
        check(Arrays.equals(ImageBlocks.quantize(edges,3,3,2,P),new byte[]{0,0,3,0,0,3,2,2,1}),"Partial block colors wrong");

        int[] panel=new int[552*768];
        for(int y=0;y<768;y++)for(int x=0;x<552;x++)panel[y*552+x]=P[(x/31+y/47)%4];
        int[] original=panel.clone();
        for(int block:new int[]{2,4,8,16}) {
            byte[] codes=ImageBlocks.quantize(panel,552,768,block,P);
            for(int y=0;y<768;y++)for(int x=0;x<552;x++)
                check(codes[y*552+x]==codes[(y/block*block)*552+x/block*block],"Block not uniform");
            check(PanelProtocol.packCodes(codes,552,768).length==105984,"Factory payload size changed");
        }
        check(Arrays.equals(panel,original),"Input mutated");
        rejected(()->ImageBlocks.quantize(new int[4],2,2,3,P));
        rejected(()->ImageBlocks.quantize(new int[3],2,2,2,P));
        rejected(()->ImageBlocks.quantize(new int[4],2,2,2,new int[2]));
        System.out.println("ImageBlocksTest: PASS (palette preservation, detail reduction, partial edges, full-panel packing, immutability, validation)");
    }
}
