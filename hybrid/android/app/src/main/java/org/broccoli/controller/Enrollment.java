package org.broccoli.controller;
import android.content.Context;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.json.JSONObject;

public final class Enrollment {
    private Enrollment() {}
    public static void install(Context context,String text) throws Exception {
        JSONObject bundle=new JSONObject(text);
        String host=bundle.getString("host"),device=bundle.getString("device");
        int port=bundle.getInt("port");
        if(!host.matches("[A-Za-z0-9.-]{1,253}") || !device.matches("[A-Za-z0-9_-]{1,64}") || port<1 || port>65535) throw new IllegalArgumentException("Invalid enrollment endpoint");
        char[] password=bundle.getString("password").toCharArray();
        KeyStore incoming=KeyStore.getInstance("PKCS12");
        incoming.load(new ByteArrayInputStream(android.util.Base64.decode(bundle.getString("pkcs12"),android.util.Base64.DEFAULT)),password);
        String alias=null;
        for(java.util.Enumeration<String> e=incoming.aliases();e.hasMoreElements();) { String a=e.nextElement();if(incoming.isKeyEntry(a)) { if(alias!=null) throw new IllegalArgumentException("Multiple private keys"); alias=a; } }
        if(alias==null) throw new IllegalArgumentException("No client key");
        Key privateKey=incoming.getKey(alias,password);
        java.util.Arrays.fill(password,'\0');
        java.security.cert.Certificate[] chain=incoming.getCertificateChain(alias);
        ((X509Certificate)chain[0]).checkValidity();
        X509Certificate ca=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bundle.getString("ca_pem").getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        ca.checkValidity();
        if(ca.getBasicConstraints()<0) throw new IllegalArgumentException("Trust anchor must be a CA");
        KeyStore android=KeyStore.getInstance("AndroidKeyStore");android.load(null);
        android.setKeyEntry("broccoli-client",privateKey,null,chain);
        try(FileOutputStream out=context.openFileOutput("ca.der",Context.MODE_PRIVATE)) { out.write(ca.getEncoded()); }
        context.getSharedPreferences("controller",0).edit().putString("enrollment",java.util.UUID.randomUUID().toString()).putString("host",host).putString("device",device).putInt("port",port).commit();
    }
    public static SSLSocketFactory sockets(Context c) throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        PrivateKey key=(PrivateKey)ks.getKey("broccoli-client",null);
        java.security.cert.Certificate[] certs=ks.getCertificateChain("broccoli-client");
        if(key==null || certs==null) throw new IllegalStateException("Import enrollment first");
        X509Certificate[] chain=java.util.Arrays.copyOf(certs,certs.length,X509Certificate[].class);
        X509KeyManager km=new X509KeyManager() {
            public String[] getClientAliases(String type,Principal[] issuers) {return new String[]{"client"};}
            public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket) {return "client";}
            public String[] getServerAliases(String type,Principal[] issuers) {return null;}
            public String chooseServerAlias(String type,Principal[] issuers,Socket socket) {return null;}
            public X509Certificate[] getCertificateChain(String alias) {return chain;}
            public PrivateKey getPrivateKey(String alias) {return key;}
        };
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);
        try(InputStream in=c.openFileInput("ca.der")) {trust.setCertificateEntry("ca",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(new KeyManager[]{km},tm.getTrustManagers(),new SecureRandom());
        return ssl.getSocketFactory();
    }
}
