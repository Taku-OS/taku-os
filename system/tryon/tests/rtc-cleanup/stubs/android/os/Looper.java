// SPDX-License-Identifier: Apache-2.0
package android.os;
import java.util.ArrayDeque;
public final class Looper {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean quit;
}
