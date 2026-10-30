ChicagoRP v0.4 - Paper 1.21.4 (Java 21), modsuz

1) PLUGIN: src/, tools/, pom.xml, README.txt dosyalarini GitHub deponun uzerine yukle -> Actions'tan jar'i indir -> plugins/'e at -> sunucuyu YENIDEN BASLAT.
   (Eski config.yml otomatik guncellenir: yeni araclar, silahlar, zirhlar, ekonomi degerleri eklenir. Eski ayarlarin: spawn, jail, places, resource-pack.url korunur.)

2) KAYNAK PAKETI: ChicagoRP-ResourcePack.zip'i GitHub'a yukle (ayni dosya adi), raw linkini config.yml -> resource-pack.url'e yaz.
   sha1 config'te hazir. /rpreload.

3) SEHIR: /sehir dunya (yeni ATM'ler, hastane, galeri icin sehri YENIDEN kurman gerekir: sehir dunya klasorunu sil).
   Yon duzeltme: /sehir tabelacevir (tabela+ATM)  /sehir araccevir (araba)  ve config models.arrow-yaw-offset (yol oku).

4) NPC KOMUTLARI (konsol olarak calistir; Citizens varsayilan konsol):  galerimenu <p>   silahcimenu <p>   bankamenu <p>
   ADMIN: /paraver /paraayarla /aracver <oyuncu> <arac> /aracsil /silahver <oyuncu> <silah> [adet] /mermiver <oyuncu> <tip> <adet>
   ARAC AYARLARI: /arac kilit | yolcu | izin <oyuncu> | at [oyuncu] | ayar | park | is
   BANKA: /banka /yatir <miktar|hepsi> /cek /havale <oyuncu> <miktar>
   CETE: /cete kur|davet|katil|ayril|baris|sembol <s>|bilgi
   Silah Ustasi: /silah uret <silah>
