// SPDX-License-Identifier: Apache-2.0
package android.widget;import android.content.Context;public class Toast{public static final int LENGTH_SHORT=0;public static String last;public static Toast makeText(Context context,String value,int duration){last=value;return new Toast();}public void show(){}}
