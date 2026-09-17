# DeadDrop

![Language](https://img.shields.io/badge/Language-Java-orange)
![Repository Size](https://img.shields.io/badge/Repository%20Size-67%20KB-brightgreen)
![License](https://img.shields.io/badge/License-MIT-blue)

## 📋 Proje Tanımı

**DeadDrop**, güvenli ve anonim dosya paylaşımı için tasarlanmış bir Java uygulamasıdır. Bu proje, kullanıcılara dosyalarını geçici olarak depolamak ve paylaşmak için güvenli bir yol sağlar.

## ✨ Özellikler

- 🔒 **Güvenli Dosya Paylaşımı** - Şifreli dosya transferi
- 📦 **Geçici Depolama** - Belirlenen süre sonra otomatik silme
- 🔗 **Paylaşılabilir Linkler** - Benzersiz URL'ler ile kolay paylaşım
- 🛡️ **Gizlilik Odaklı** - Anonim kullanıcı desteği
- ⚡ **Hızlı İşlem** - Verimli Java mimarisi

## 🚀 Hızlı Başlangıç

### Sistem Gereksinimleri

- Java 11 veya üzeri
- Maven 3.6.0 veya üzeri
- Git

### Kurulum

1. Projeyi klonlayın:
```bash
git clone https://github.com/BullLazy/DeadDrop.git
cd DeadDrop
```

2. Bağımlılıkları yükleyin:
```bash
mvn install
```

3. Uygulamayı çalıştırın:
```bash
mvn spring-boot:run
```

## 📁 Proje Yapısı

```
DeadDrop/
├── src/
│   ├── main/
│   │   ├── java/          # Ana Java kaynak dosyaları
│   │   └── resources/     # Konfigürasyon dosyaları
│   └── test/              # Test dosyaları
├── pom.xml                # Maven konfigürasyonu
└── README.md              # Bu dosya
```

## 🔧 Kullanım

### API Endpointleri

- **POST /api/upload** - Dosya yükleme
  ```bash
  curl -X POST -F "file=@dosya.txt" http://localhost:8080/api/upload
  ```

- **GET /api/download/{token}** - Dosya indirme
  ```bash
  curl http://localhost:8080/api/download/{token}
  ```

- **DELETE /api/file/{token}** - Dosya silme
  ```bash
  curl -X DELETE http://localhost:8080/api/file/{token}
  ```

## 🧪 Test Çalıştırma

```bash
mvn test
```

## 📝 Katkıda Bulunma

Katkılar kabul edilir! Lütfen şu adımları izleyin:

1. Projeyi fork edin
2. Feature branch'i oluşturun (`git checkout -b feature/AmazingFeature`)
3. Değişiklikleri commit edin (`git commit -m 'Add some AmazingFeature'`)
4. Branch'e push edin (`git push origin feature/AmazingFeature`)
5. Pull Request açın

## 📄 Lisans

Bu proje MIT Lisansı altında lisanslanmıştır. Detaylar için [LICENSE](LICENSE) dosyasını görüntüleyin.

## 👤 Yazar

**BullLazy** - [@BullLazy](https://github.com/BullLazy)

## 🤝 Destek

Sorularınız veya sorunlarınız için lütfen [Issues](https://github.com/BullLazy/DeadDrop/issues) bölümünü kullanın.

## 📞 İletişim

- GitHub: [@BullLazy](https://github.com/BullLazy)
- Proje Repository: [DeadDrop](https://github.com/BullLazy/DeadDrop)

---

**Son Güncelleme:** 2026-09-15

⭐ Projeyi yararlı bulduysanız, lütfen yıldız veriniz!
