// SPDX-License-Identifier: Apache-2.0
package android.graphics;

public class Rect {
    public int left,top,right,bottom;
    public Rect(){}
    public Rect(int left,int top,int right,int bottom){this.left=left;this.top=top;this.right=right;this.bottom=bottom;}
    public Rect(Rect other){set(other);}
    public void set(Rect other){left=other.left;top=other.top;right=other.right;bottom=other.bottom;}
    public int width(){return right-left;}
    public int height(){return bottom-top;}
    public boolean isEmpty(){return left>=right||top>=bottom;}
    public boolean contains(Rect other){return !isEmpty()&&left<=other.left&&top<=other.top&&right>=other.right&&bottom>=other.bottom;}
    public static boolean intersects(Rect a,Rect b){return a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom;}
}
