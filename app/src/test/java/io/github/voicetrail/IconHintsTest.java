package io.github.voicetrail;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;

public class IconHintsTest {
    private static final int DARK = 0xff101010, LIGHT = 0xfffafafa;

    @Test public void distinguishesPlusAndCloseInEitherPolarity() {
        for (int shape=1; shape<=3; shape+=2) {
            for (int bg : new int[]{DARK,LIGHT}) {
                int[] pixels = new int[64*64];
                Arrays.fill(pixels,bg);
                for (int y=-15; y<=15; y++) for (int x=-15; x<=15; x++) {
                    boolean ink = shape==3 ? Math.abs(x)<=2 || Math.abs(y)<=2
                            : Math.abs(Math.abs(x)-Math.abs(y))<=2;
                    if (ink) pixels[(32+y)*64+32+x] = bg==DARK ? LIGHT : DARK;
                }
                assertEquals(shape,IconHints.symbol(pixels,64,64));
            }
        }
    }

    @Test public void blankAndMixedGlyphsStayUnknown() {
        int[] pixels=new int[64*64];
        Arrays.fill(pixels,LIGHT);
        assertEquals(-1,IconHints.symbol(pixels,64,64));
        for (int y=15; y<49; y++) for (int x=15; x<49; x++)
            if ((x%8)<3 || (y%8)<3) pixels[y*64+x]=DARK;
        assertEquals(-1,IconHints.symbol(pixels,64,64));
    }

    @Test public void distinguishesBackArrowFromMagnifier() {
        int[] arrow=new int[64*64], glass=new int[64*64];
        Arrays.fill(arrow,LIGHT);
        Arrays.fill(glass,LIGHT);
        for (int y=0; y<64; y++) for (int x=0; x<64; x++) {
            int dx=x-32, dy=y-32;
            if (Math.abs(dx)<=15 && Math.abs(dy)<=15 &&
                    (Math.abs(dy)<=2 || (dx<=0 && Math.abs(Math.abs(dy)-15-dx)<=2))) arrow[y*64+x]=DARK;
            if (Math.abs(Math.hypot(x-28,y-28)-11)<=1.8 ||
                    (x>=36 && x<=48 && Math.abs(x-y)<=2)) glass[y*64+x]=DARK;
        }
        assertEquals(0,IconHints.symbol(arrow,64,64));
        assertEquals(2,IconHints.symbol(glass,64,64));
    }
}
