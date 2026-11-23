// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
/** API-stop before Service creation and late callbacks cannot admit or release another generation. */
public final class StartAdmissionTest {
    public static void main(String[] args){
        StartAdmission a=new StartAdmission();check(!a.busy());long first=a.reserve();check(first>0&&a.accepted(first));
        a.cancel();check(!a.accepted(first)&&a.busy());check(a.reserve()==0);a.release(first+1);check(a.busy());
        a.release(first);check(!a.busy());long second=a.reserve();check(second>first&&a.accepted(second));
        a.release(first);check(a.busy()&&a.accepted(second));a.cancel();check(!a.accepted(second));
        a.release(second);check(!a.busy());System.out.println("StartAdmissionTest: 10 pending-launch/generation checks passed; no Android or model");
    }
    static void check(boolean b){if(!b)throw new AssertionError();}
}
