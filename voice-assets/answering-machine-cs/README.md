# Обычный автоответчик закрытых часов — чешское приветствие

Голос: DPP Praha Voice (Fish Audio, `reference_id=5af7cba2eb104bcc9f249c96ae450800`),
модель `s2.1-pro-free`, скорость 1.2. Установлен на устройство:
`Settings.amGreetingFile` (`amGreetingSource=1`).

- `greeting_with_beep.mp3` — «Momentálně nemůžeme přijmout váš hovor. Napište
  nám prosím SMS nebo zanechte hlasovou zprávu a my se vám ozveme. Mluvíme
  česky, anglicky a rusky.» Речь нормализована (loudnorm −18 LUFS) поверх
  фоновой музыки (../music/Glass_Wall_Afternoon.mp3), музыка гаснет к концу
  речи, дальше пауза 1.2с и короткий тихий бип (0.6с, −1дБ) — вшит в файл
  вручную, поэтому `Greeting.prepare()` не добавляет свой бип поверх
  (см. `useFile` в `Greeting.kt`, начиная с v0.24.1).
