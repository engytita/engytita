package io.engytita.proxy.tls;

import java.util.concurrent.TimeUnit;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.engytita.proxy.ProxyConfig;

public class CertManager {

   private static final int CERT_CACHE_SIZE = 2000;

   private final LoadingCache<String, Certificate> certsCache;

   public CertManager(ProxyConfig config) {
      this.certsCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.DAYS)
            .maximumSize(CERT_CACHE_SIZE)
            .build(new CacheLoader(config.getCertificate(), config.getKey()));
   }

   public Certificate getCert(String host) {
      return certsCache.get(host);
   }

   static class CacheLoader implements com.github.benmanes.caffeine.cache.CacheLoader<String, Certificate> {

      X509CertificateHolder certificate;
      PrivateKeyInfo key;

      public CacheLoader(X509CertificateHolder certificate, PrivateKeyInfo key) {
         this.certificate = certificate;
         this.key = key;
      }

      public Certificate load(String host) {
         return CertUtil.newCert(certificate, key, host);
      }
   }
}
