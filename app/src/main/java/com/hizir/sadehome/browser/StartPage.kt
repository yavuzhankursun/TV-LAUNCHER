package com.hizir.sadehome.browser

import android.net.Uri
import android.text.Html

/** Tarayıcının açılış sayfası: arama kutusu ve büyük yer imi kutucukları (imleçle kolay hedeflenir). */
object StartPage {
    private fun esc(s: String) = Html.escapeHtml(s)

    fun html(bookmarks: List<Pair<String, String>>): String {
        val tiles = bookmarks.joinToString("") { (title, url) ->
            val host = Uri.parse(url).host.orEmpty()
            """<a class="tile" href="${esc(url)}">
                 <img src="https://www.google.com/s2/favicons?domain=${esc(host)}&sz=64" onerror="this.style.visibility='hidden'">
                 <span>${esc(title)}</span></a>"""
        }
        return """<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Yeni sekme</title>
<style>
  html,body{margin:0;height:100%;background:radial-gradient(circle at 80% 10%,#2a1a4a 0,#0e0e12 55%);color:#fff;
    font-family:sans-serif}
  main{max-width:860px;margin:0 auto;padding:48px 24px}
  h1{font-size:30px;font-weight:800;letter-spacing:-.5px;margin:0 0 20px}
  form{display:flex;margin-bottom:34px}
  input{flex:1;font-size:20px;padding:14px 20px;border-radius:30px;border:2px solid rgba(255,255,255,.18);
    background:rgba(255,255,255,.08);color:#fff;outline:none}
  input:focus{border-color:#fff}
  .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}
  .tile{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;height:120px;
    border-radius:18px;background:rgba(255,255,255,.07);color:#fff;text-decoration:none;font-size:15px;
    font-weight:600;border:2px solid transparent;transition:transform .12s,background .12s}
  .tile:hover{background:rgba(255,255,255,.16);border-color:#fff;transform:scale(1.04)}
  .tile img{width:36px;height:36px;border-radius:8px}
  p{opacity:.55;font-size:13px;margin-top:28px}
</style></head><body><main>
<h1>Nereye gidelim?</h1>
<form action="https://www.google.com/search" method="get">
  <input name="q" placeholder="Google'da ara" autocomplete="off">
</form>
<div class="grid">$tiles</div>
<p>Yön tuşları imleci taşır · OK tıklar · basılı tut hızlanır · kenara it: sayfa kayar · en üstte yukarı: adres çubuğu · ★ ana sayfaya ekler</p>
</main></body></html>"""
    }
}
