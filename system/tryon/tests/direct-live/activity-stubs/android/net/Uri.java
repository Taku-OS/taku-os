// SPDX-License-Identifier: Apache-2.0
package android.net;public class Uri{final String raw;private Uri(String raw){this.raw=raw;}public static Uri parse(String raw){return new Uri(raw);}public String getScheme(){int at=raw.indexOf(':');return at<0?null:raw.substring(0,at);}public String toString(){return raw;}}
