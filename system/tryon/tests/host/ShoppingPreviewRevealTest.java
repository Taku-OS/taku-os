package org.taku.tryon.realtime;

public final class ShoppingPreviewRevealTest {
    public static void main(String[] args) {
        ShoppingPreviewReveal gate = new ShoppingPreviewReveal();
        check(gate.claim(false)==0,"local camera is never a returned result");
        long first=gate.claim(true);
        check(gate.current(first),"first decoded return owns reveal");
        check(gate.claim(true)==0,"later frames cannot reopen presentation");
        gate.interrupt(); check(!gate.current(first),"user action fences pending fullscreen completion");
        ShoppingPreviewReveal touched=new ShoppingPreviewReveal();
        touched.interrupt(); check(touched.claim(true)==0,"late first result cannot override manual choice");
        check(!new ShoppingPreviewReveal().current(0),"absent ticket cannot complete");
        check(ShoppingPreviewReveal.showCameraImmediately(true,true),"character shows the raw camera while its model is connecting");
        check(!ShoppingPreviewReveal.showCameraImmediately(true,false),"ordinary direct outfit retains result-driven reveal");
        check(ShoppingPreviewReveal.showCameraImmediately(false,false),"legacy local-camera reveal is unchanged");
        ShoppingPreviewReveal character=new ShoppingPreviewReveal();
        check(character.claim(false,true)==0,"local camera never counts as a model result");
        check(character.claim(true,true)==0&&character.claim(true,true)==0,"character results cannot force fullscreen or reopen a moved window");
        check(new ShoppingPreviewReveal().claim(true,false)!=0,"outfit retains its original automatic first-result reveal");
        System.out.println("ShoppingPreviewRevealTest: 12 checks passed");
    }
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
}
