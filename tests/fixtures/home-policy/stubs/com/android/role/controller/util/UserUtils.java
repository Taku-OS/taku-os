package com.android.role.controller.util; import android.content.Context;import android.os.UserHandle;
public class UserUtils {public static boolean isProfile(UserHandle u,Context c){return u.profile;} public static Context getUserContext(Context c,UserHandle u){return c.users.getOrDefault(u.id,c);}}
