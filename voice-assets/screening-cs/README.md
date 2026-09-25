# Скрининг звонков — чешские аудио (voice-clone Fish Audio)

Голос: DPP Praha Voice (Fish Audio, `reference_id=5af7cba2eb104bcc9f249c96ae450800`),
модель `s2.1-pro-free`, скорость 1.2. Установлены на устройство:
`Settings.screeningGreetingFileCs` / `Settings.screeningHoldFileCs`.

- `greeting.mp3` — приветствие: «Dobrý den. Dovolali jste se do společnosti Big
  Tweak. Zůstaňte prosím na lince, spojíme vás s operátorem.»
- `hold_loop_30s.mp3` — фраза «Pokud nechcete čekat na lince, napište nám SMS se
  svým dotazem. Odpovíme vám a co nejdříve vám zavoláme zpět.» поверх фоновой
  музыки (Glass_Wall_Afternoon, сгенерирована Gemini пользователем), 30с,
  музыка приглушается на время речи и поднимается обратно после. Тайлится
  приложением (`Greeting.tileToLength`) на всё время ожидания.
