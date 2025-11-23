Welcome! / Benvenuti!

About This Fork:

This project began with a clear goal: to fix the notoriously high decoding latency on MediaTek (Mtk) devices. The initial approach involved enabling RFI for the c2.mtk and omx.mtk decoders and adjusting specific MediaTek keys within the MediacodecHelper.

Through further experimentation, I've also implemented LFR (Prefer Lower Delays) , which successfully reduces the total end-to-end latency by a few milliseconds on many devices.

A Word of Caution: Here Be Dragons! 🐲

This codebase is my personal playground. As such, please be aware of the following:

Chaotic Development: My workflow is experimental and fast-paced. You will find multiple branches, occasional force pushes, and a mix of brilliant ideas and terrible ones.

Code & Comments: The source contains a blend of Italian and English comments, some code snippets generated with the help of AI (like ChatGPT Plus), and a few bugs that inevitably slip through.

Testing Limitations: I do my best to test and debug thoroughly before any release, but my resources are limited. I can only validate changes on my own hardware.

I work on this project for fun and the thrill of discovery. This means I often implement new features that might be challenging to maintain in the long run.

Thank you for taking the time to read this. I hope you find my work useful!

Enjoy!

Ciao!

— DerFlacco


