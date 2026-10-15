# Third-party software in Killa YuE

The APK contains the components below. Each is used under its own licence, reproduced here.

## yue2.cpp (the song engine), by Serveurperso

```
MIT License

Copyright (c) 2023-2026 The yue2.cpp authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## GGML (tensor library inside the engine)

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Mesa, Turnip Vulkan driver (built with two patches, see engine/)

```
License and Copyright
=====================

Disclaimer
----------

Mesa implements various APIs, including `OpenGL`_, `OpenGL ES`_,
`Vulkan`_ and `OpenCL`_. Even though Mesa implements these APIs, the
implementation isn't formally conformant on all combinations of drivers
and hardware. `Khronos`_ maintains lists of conformant implementations
for each of their APIs, as well as `trademark details`_.

This project is called *Mesa*, *Mesa3D*, or *The Mesa 3-D graphics library*.

.. _OpenGL: https://www.opengl.org/
.. _OpenGL ES: https://www.khronos.org/opengles/
.. _Vulkan: https://www.vulkan.org/
.. _OpenCL: https://www.khronos.org/opencl/
.. _Khronos: https://www.khronos.org/
.. _trademark details: https://www.khronos.org/legal/trademarks/

License / Copyright Information
-------------------------------

The Mesa distribution consists of several components. Different
copyrights and licenses apply to different components. For example, the
GLX client code uses the SGI Free Software License B, and some of the
Mesa device drivers are copyrighted by their authors. See below for a
list of Mesa's main components and the license for each.

The core Mesa library is licensed according to the terms of the MIT
license.

Most of the Mesa code is licensed under MIT license,
but individual files may have their own licenses.

You may find all the licenses used within this project in the licenses/ directory.

Attention, Contributors
-----------------------

When contributing to the Mesa project you must agree to the licensing
terms of the component to which you're contributing. The following
section lists the primary components of the Mesa distribution and their
respective licenses.

Mesa Component Licenses
-----------------------

In general, consult each source file SPDX identifier for license terms.
```

## zlib

```
Format: http://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: zlib
Upstream-Contact: zlib@gzip.org
Source: http://zlib.net/
Comment: This is the pre-packaged Debian Linux version of the zlib compression
 library.  It was packaged by Michael Alan Dorman <mdorman@debian.org>
 from sources originally retrieved from ftp.uu.net in the directory
 /pub/archiving/zip/zlib as the file zlib-1.0.4.tar.gz.
 .
 The deflate format used by zlib was defined by Phil Katz. The deflate
 and zlib specifications were written by Peter Deutsch. Thanks to all the
 people who reported problems and suggested various improvements in zlib;
 they are too numerous to cite here.
Files-Excluded:
 contrib/ada
 contrib/amd64
 contrib/asm686
 contrib/blast
 contrib/delphi
 contrib/dotzlib
 contrib/gcc_gvmat64
 contrib/infback9
 contrib/inflate86
 contrib/iostream
 contrib/iostream2
 contrib/iostream3
 contrib/masmx64
 contrib/masmx86
 contrib/pascal
 contrib/puff
 contrib/testzlib
 contrib/untgz
 contrib/vstudio
 doc/rfc1950.txt
 doc/rfc1951.txt
 doc/rfc1952.txt
 win32

Files: *
Copyright: 1995-2013 Jean-loup Gailly and Mark Adler
License: Zlib

Files: amiga/Makefile.pup
Copyright: 1998 by Andreas R. Kleinert
License: Zlib

Files: contrib/minizip/*
Copyright: 1998-2010 Gilles Vollant
           2007-2008 Even Rouault
           2009-2010 Mathias Svensson
License: Zlib

Files: debian/*
Copyright: 2000-2017 Mark Brown
License: Zlib

License: Zlib
 This software is provided 'as-is', without any express or implied
 warranty.  In no event will the authors be held liable for any damages
 arising from the use of this software.
 .
 Permission is granted to anyone to use this software for any purpose,
 including commercial applications, and to alter it and redistribute it
 freely, subject to the following restrictions:
 .
 1. The origin of this software must not be misrepresented; you must not
    claim that you wrote the original software. If you use this software
    in a product, an acknowledgment in the product documentation would be
    appreciated but is not required.
 2. Altered source versions must be plainly marked as such, and must not be
    misrepresented as being the original software.
 3. This notice may not be removed or altered from any source distribution.
 .
 Jean-loup Gailly        Mark Adler
 jloup@gzip.org          madler@alumni.caltech.edu
 .
 If you use the zlib library in a product, we would appreciate *not* receiving
 lengthy legal documents to sign.  The sources are provided for free but without
 warranty of any kind.  The library has been entirely written by Jean-loup
 Gailly and Mark Adler; it does not include third-party code.
 .
 If you redistribute modified sources, we would appreciate that you include in
 the file ChangeLog history information documenting your changes.  Please read
 the FAQ for more information on the distribution of modified source versions.
```

## Zstandard

```
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: Zstd
Source: https://github.com/facebook/zstd
Files-Excluded: build/VS2008
                build/VS2010
                build/VS_scripts
                programs/windres/*
                .buckversion
                .buckconfig
                .circleci/*
                .cirrus.yml

Files: *
Copyright: Yann Collet
	   Przemyslaw Skibinski
	   Martin Liska
	   SUSE
	   Meta Platforms, Inc. and affiliates
License: BSD-3-clause or GPL-2
Comment: Starting from 1.3.1 zstd's patent claim is removed
 see: https://github.com/facebook/zstd/pull/801

Files: zlibWrapper/examples/*.c
Copyright: 1995-2006, 2011 Jean-loup Gailly
License: zlib

Files: zlibWrapper/gz*.c
Copyright: (C) 2004, 2005, 2010, 2011, 2012, 2013 Mark Adler
License: zlib

License: zlib
 This software is provided 'as-is', without any express or implied
 warranty. In no event will the authors be held liable for any damages
 arising from the use of this software.
 .
 Permission is granted to anyone to use this software for any purpose,
 including commercial applications, and to alter it and redistribute it
 freely, subject to the following restrictions:
 .
 1. The origin of this software must not be misrepresented; you must not
    claim that you wrote the original software. If you use this software
    in a product, an acknowledgement in the product documentation would be
    appreciated but is not required.
 2. Altered source versions must be plainly marked as such, and must not be
    misrepresented as being the original software.
 3. This notice may not be removed or altered from any source distribution.

Files: lib/dictBuilder/divsufsort.*
Copyright: 2003-2008, Yuta Mori
License: Expat

Files: examples/*
Copyright: 2016-present, Yann Collet, Facebook, Inc.
License: BSD-3-clause or GPL-2

Files: debian/*
Copyright:
 2015-2016 Kevin Murray <spam@kdmurray.id.au>
 2022-2026 Peter Pentchev <roam@debian.org>
License: Expat

License: Expat
 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:
 .
 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.
 .
 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.

License: GPL-2
 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License, v2, as
 published by the Free Software Foundation
 .
 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.
 .
 You should have received a copy of the GNU General Public License along
 with this program; if not, write to the Free Software Foundation, Inc.,
 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 .
 On Debian systems, the complete text of the GNU General Public
 License version 2 can be found in `/usr/share/common-licenses/GPL-2'.

License: BSD-3-clause
 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:
     * Redistributions of source code must retain the above copyright
       notice, this list of conditions and the following disclaimer.
     * Redistributions in binary form must reproduce the above copyright
       notice, this list of conditions and the following disclaimer in the
       documentation and/or other materials provided with the distribution.
     * Neither the name of cereal nor the
       names of its contributors may be used to endorse or promote products
       derived from this software without specific prior written permission.
 .
 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL RANDOLPH VOORHIES OR SHANE GRANT BE LIABLE FOR ANY
 DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Expat

```
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: Expat
Upstream-Contact: Sebastian Pipping <sebastian@pipping.org>
Source: https://github.com/libexpat/libexpat
Copyright: Copyright (C) Expat maintainers

Files: *
Copyright:
 Copyright (C) 1998-2000 Thai Open Source Software Center Ltd and Clark Cooper
 Copyright (C) 2001-2017 Expat maintainers
License: MIT

Files: debian/*
Copyright:
 Copyright (C) 1998-2000 Adam Di Carlo <aph@debian.org>
 Copyright (C) 2000-2007 Ardo van Rangelrooij <ardo@debian.org>
 Copyright (C) 2008-2009 Daniel Leidert (dale) <daniel.leidert@wgdd.de>
 Copyright (C) 2013-     Laszlo Boszormenyi (GCS) <gcs@debian.org>
License: MIT

License: MIT
 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to
 deal in the Software without restriction, including without limitation the
 rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 sell copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:
 .
 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.
 .
 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 IN THE SOFTWARE.
```

## Killa typefaces (Killa Gel Bold Script, Killa Meridian Text)

```
Copyright 2026 JackKnifeAI (Killa Studio project).
Killa Gel, Killa Meridian, Killa Cadence, Killa Harmonic and Killa Pi Phi are original typefaces created for Killa Studio.

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
http://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```

## Khronos Vulkan-Loader

Copyright (c) 2014-2025 The Khronos Group Inc., Valve Corporation, LunarG, Inc. Licensed under the Apache License, Version 2.0; the full text is in the LICENSE file of this repository.

## libc++ (LLVM)

Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions. See https://llvm.org/LICENSE.txt

## Qualcomm AI Engine Direct (QNN) runtime 2.50 (album covers)

The APK contains four libraries from Qualcomm's QNN runtime, version 2.50.0 (Maven Central, `com.qualcomm.qti:qnn-runtime`): `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV79Stub.so` and `libQnnHtpV79Skel.so`. They are licensed under the **Qualcomm AI Hub Model License** (https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf), which allows them to be distributed in object code as part of an application, and not on their own. They are not covered by this project's Apache License, are not in this repository, and that licence forbids reverse engineering them.

## QNN API headers (qualcomm/geniex-qairt-plugin)

The cover painter (`engine/npu/sdnpu.cpp`, shipped as `libsdnpu.so`) is compiled against the QNN API headers from https://github.com/qualcomm/geniex-qairt-plugin.

```
Copyright 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.

Redistribution and use in source and binary forms, with or without modification, are permitted
provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and
the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions
and the following disclaimer in the documentation and/or other materials provided with the
distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse
or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## CLIP tokenizer vocabulary (OpenAI CLIP)

`app/assets/clip/bpe_simple_vocab_16e6.txt.gz` comes from https://github.com/openai/CLIP.

```
MIT License

Copyright (c) 2021 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## QR Code generator (Project Nayuki)

The QR codes for sharing are drawn by `app/src/io/nayuki/qrcodegen`, from https://github.com/nayuki/QR-Code-generator.

```
QR Code generator library (Java)

Copyright (c) Project Nayuki. (MIT License)
https://www.nayuki.io/page/qr-code-generator-library

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:
- The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
- The Software is provided "as is", without warranty of any kind, express or
implied, including but not limited to the warranties of merchantability,
fitness for a particular purpose and noninfringement. In no event shall the
authors or copyright holders be liable for any claim, damages or other
liability, whether in an action of contract, tort or otherwise, arising from,
out of or in connection with the Software or the use or other dealings in the
Software.
```

## stable-diffusion.cpp (GPU covers)

Phones without the Snapdragon 8 Elite's NPU paint covers with `sd-cli` from https://github.com/leejet/stable-diffusion.cpp, built for Android and shipped as `libsdgpu.so` (it includes GGML, MIT).

```
MIT License

Copyright (c) 2023 leejet

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## whisper.cpp (lyric sync)

Lyric sync runs `whisper-cli` from https://github.com/ggml-org/whisper.cpp, built for Android and shipped as `libwhisper.so` (it includes GGML, MIT).

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## abcjs 6.7.1 (sheet music layout), by Paul Rosen and Gregory Dyke

Bundled as `app/assets/abcjs/abcjs-basic-min.js`, run offline inside the app. One change: tablature accepts a
re-entrant tuning (a string higher than the one above it, as on a ukulele or a 5-string banjo) instead of
rejecting it; in `tab-notes.js` the "Invalid string Instrument tuning" error became `return []`. That string's
note list is then empty, and single notes never land on it, because abcjs picks the highest string that
can play a note.

```
Copyright (c) 2009-2026 Paul Rosen and Gregory Dyke

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

**This text is from: http://opensource.org/licenses/MIT**
```

## Lyric sync model (not included)

On request the app downloads OpenAI's Whisper "small" model in whisper.cpp's format (`ggerganov/whisper.cpp`, `ggml-small-q5_1.bin`), MIT licence. It is not part of this repository or of the APK.

## GPU cover models (not included)

On request the app downloads, from Hugging Face: Stable Diffusion 1.5 as GGUF (`second-state/stable-diffusion-v1-5-GGUF`, CreativeML OpenRAIL-M), the LCM LoRA for SD 1.5 (`latent-consistency/lcm-lora-sdv1-5`, OpenRAIL++), and the TAESD decoder (`madebyollin/taesd`, MIT). Their use restrictions apply to everyone who uses them. They are not part of this repository or of the APK.

## Stable Diffusion 1.5 cover model (not included)

The app downloads Stable Diffusion 1.5, as compiled by Qualcomm for the Snapdragon 8 Elite, at the user's request from Qualcomm's AI Hub release (Hugging Face `qualcomm/Stable-Diffusion-v1.5`). The weights are licensed CreativeML OpenRAIL-M, whose use restrictions apply to everyone who uses them. They are not part of this repository or of the APK.

## YuE2 model weights (not included)

The app downloads the YuE2 weights at the user's request from Hugging Face. They are the work of M-A-P and are licensed CC BY-NC 4.0 with the authors' additional creator permission. They are not part of this repository or of the APK.
