// SPDX-License-Identifier: Apache-2.0
package android.content.pm;public class PackageManager{public static String sourcePackage="org.agentos.shopping";public ProviderInfo resolveContentProvider(String authority,int flags){if(sourcePackage==null)return null;ProviderInfo value=new ProviderInfo();value.packageName=sourcePackage;return value;}}
