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
        System.out.println("ShoppingPreviewRevealTest: 6 checks passed");
    }
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
}
