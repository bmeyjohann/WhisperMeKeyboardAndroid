# WhisperMe for Android

WhisperMe uses the FlorisBoard keyboard foundation; upstream documentation and license information are retained below.

After keyboard setup, the app opens to **Transcripts**, including when signed out. A sign-in notice stays above the transcript list. From home, open **Voice settings** for your account and optional saved writing preferences, or **Keyboard settings** for languages, appearance, and key feedback. Back returns to Transcripts.

**Advanced settings** starts collapsed. Voice providers, models, server endpoint, processing mode, experimental questions, and opt-in context diagnostics live there. Additional keyboard customization remains under Keyboard settings > Advanced settings. Existing saved settings are preserved; opening settings does not enable preferences or diagnostics. New/unset LLM preferences use **WhisperMe default** (`llmRouting: server`); saved Groq/Cerebras selections stay explicit. Choosing the server default preserves the saved explicit model without transmitting it. The advanced reasoning control defaults to the provider/server default (no override). Qwen and Gemma offer None/Low/Medium/High; GPT-OSS omits None. Unsupported saved levels safely fall back to Default and the effective request level is retained in history.

Tap the smartbar WhisperMe icon to toggle recording. Holding for at least one second finishes on release; gesture cancellation also cancels an asynchronous start. Connection timing and provider retries appear separately from captions. Missing heartbeats describe the connection to WhisperMe with an unknown cause; only OS offline state is labeled offline. The server owns the maximum two automatic retries. Server budget warnings from WebSocket and HTTP (batch, transcript continuation, and style extraction) appear separately and persist as last-reported accounting metadata; voice sessions also retain their own budget snapshot, and structured budget-exhausted failures preserve the transcript without automatic retries.

**Transcripts > Export JSON** lets you select individual sessions or select all, then save through the Android document picker. Nothing is uploaded automatically. The versioned `whisperme.history` JSON contains stable `caseId` values, transcript text with an explicit `transcriptFinal` flag, results, status, requested/resolved model metadata, reasoning effort, and millisecond timings (unknown timings stay null). It excludes diagnostics, editor context, prompts, recovery payloads, debug events, audio, and credentials. Dictated content is preserved verbatim: review/redact the saved file before sharing. The separate **Copy full technical details** action still includes explicitly enabled diagnostics.

Recovery stays with the verified input session. Changing fields returns to the regular keyboard; insertion still verifies the original text and selection. History preserves complete transcripts, results, structured failures, and a token/audio/context-free continuation payload. **Continue final processing** uses WebSocket transcript resume when advertised (HTTP fallback otherwise). In-panel continuation can use the original transient editor snapshot; history continuation is explicitly copy-only and does not restore editor context or saved instructions. Partial transcripts never auto-resume. Retained full PCM remains bounded in memory, separate from history, for manual retranscription while the original context remains available.

**Voice settings > Saved preferences** manages cloud writing and punctuation rules, the device-local dictionary, app categories, and existing local writing styles. Rules show readable app/category/all-app scopes. Category assignments are chosen by the user using the fixed Personal/social, Business/work, Development, Writing/publishing, Notes/planning, Study/learning, and Other catalog. The assigned catalog ID travels with batch, realtime, and style-extraction requests. Existing local styles and legacy category/app assignments remain available, with an explicit category picker for migration.

Ordinary correction suggestions appear as a compact smartbar chip alongside normal keyboard controls. Tap to edit a word or writing rule, review its suggested scope/reason, and press **Save**. Typing, a field change, or a new recording dismisses an unsaved draft. All inferred drafts require an explicit Save. Standalone memory/control confirmations are notice-only and never insert text; legacy ambiguous confirmations remain locked to their original app. Scoped confirmations unlock the scope picker only when the server explicitly returns `scopeLocked: false` for the advertised `supportsScopedPreferenceConfirmation` capability. Android also advertises `supportsDictionaryConfirmation`; ordinary dictionary corrections omit or set `requiresConfirmation: false` so their corrected text still inserts.

Dictionary words apply in all apps on this device. Saving preserves the separate dictionary, writing-preference, and suggestion opt-in switches. If dictionary use is off, an explicitly offered draft can still be saved without enabling it. New words affect the next recording. With dictionary use enabled, words are sent as Muse keyword hints and AI spelling hints. The dictionary allows up to 100 entries of 80 characters each and 4,096 total UTF-8 bytes (about 1,024 tokens). An oversized legacy dictionary stays available for editing and is rejected before transmission rather than partially sent.

**Add writing rule / Extract from examples** accepts up to five examples, totalling 12,000 characters, and returns an editable draft with name, scope, category/app target, and optional reason. Extraction sends the examples to WhisperMe and does not save the result. Rules allow 2,000 characters and 8,000 UTF-8 bytes each, up to 100 saved rules / 64,000 bytes, and 16,000 active bytes including the selected local style (about 4,000 tokens). Bytes are actual caps; token counts are estimates. Server errors appear in the editor or voice recovery flow.

## Upstream FlorisBoard documentation

<img align="left" width="80" height="80"
src=".github/repo_icon.png" alt="App icon">

# FlorisBoard [![Crowdin](https://badges.crowdin.net/florisboard/localized.svg)](https://crowdin.florisboard.patrickgold.dev) [![Matrix badge](https://img.shields.io/badge/chat-%23florisboard%3amatrix.org-blue)](https://matrix.to/#/#florisboard:matrix.org) [![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](CODE_OF_CONDUCT.md) [![FlorisBoard CI](https://github.com/florisboard/florisboard/actions/workflows/android.yml/badge.svg?event=push)](https://github.com/florisboard/florisboard/actions/workflows/android.yml)

**FlorisBoard** is a free and open-source keyboard for Android 7.0+
devices. It aims at being modern, user-friendly and customizable while
fully respecting your privacy. Currently in early-beta state.

<table>
<tr>
<th align="center" width="50%">
<h3>Stable <a href="https://github.com/florisboard/florisboard/releases/latest"><img alt="Latest stable release" src="https://img.shields.io/github/v/release/florisboard/florisboard?sort=semver&display_name=tag&color=28a745"></a></h3>
</th>
<th align="center" width="50%">
<h3>Preview <a href="https://github.com/florisboard/florisboard/releases"><img alt="Latest preview release" src="https://img.shields.io/github/v/release/florisboard/florisboard?include_prereleases&sort=semver&display_name=tag&color=fd7e14"></a></h3>
</th>
</tr>
<tr>
<td valign="top">
<p><i>Major versions only</i><br><br>Updates are more polished, new features are matured and tested through to ensure a stable experience.</p>
</td>
<td valign="top">
<p><i>Major + Alpha/Beta/Rc versions</i><br><br>Updates contain new features that may not be fully matured yet and bugs are more likely to occur. Allows you to give early feedback.</p>
</td>
</tr>
<tr>
<td valign="top">
<p><a href="https://f-droid.org/packages/dev.patrickgold.florisboard"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="64" alt="F-Droid badge"></a></p>
<p>

**Google Play**: Join the [FlorisBoard Test Group](https://groups.google.com/g/florisboard-public-alpha-test), then visit the [testing page](https://play.google.com/apps/testing/dev.patrickgold.florisboard). Once joined and installed, updates will be delivered like for any other app. ([Store entry](https://play.google.com/store/apps/details?id=dev.patrickgold.florisboard))

</p>
<p>

**Obtainium**: [Auto-import stable config][obtainium_stable]

</p>
<p>

**Manual**: Download and install the APK from the release page.

</p>
</td>
<td valign="top">
<p><a href="https://apt.izzysoft.de/fdroid/index/apk/dev.patrickgold.florisboard.beta"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" height="64" alt="IzzySoft repo badge"></a></p>
<p>

**Google Play**: Join the [FlorisBoard Test Group](https://groups.google.com/g/florisboard-public-alpha-test), then visit the [preview testing page](https://play.google.com/apps/testing/dev.patrickgold.florisboard.beta). Once joined and installed, updates will be delivered like for any other app. ([Store entry](https://play.google.com/store/apps/details?id=dev.patrickgold.florisboard.beta))

</p>
<p>

**Obtainium**: [Auto-import preview config][obtainium_preview]

</p>
<p>

**Manual**: Download and install the APK from the release page.

</p>
</td>
</tr>
</table>

Beginning with v0.6.0 FlorisBoard will enter the public beta on Google Play.

## Highlighted features
- Integrated clipboard manager / history
- Advanced theming support and customization
- Integrated extension support (still evolving)
- Emoji keyboard / history / suggestions

> [!IMPORTANT]
> Word suggestions/spell checking are not included in the current releases
> and are a major goal for the v0.5 milestone.

Feature roadmap: See [ROADMAP.md](ROADMAP.md)

## Contributing
Want to contribute to FlorisBoard? That's great to hear! There are lots of
different ways to help out, please see the [contribution guidelines](CONTRIBUTING.md) for more info.

## Addons Store
The official [Addons Store](https://beta.addons.florisboard.org) offers the possibility for the community to share and download FlorisBoard extensions.
Instructions on how to publish addons can be found [here](https://github.com/florisboard/florisboard/wiki/How-to-publish-on-FlorisBoard-Addons).

Many thanks to Ali ([@4H1R](https://github.com/4H1R)) for implementing the store!

> [!NOTE]
> During the initial beta release phase, the Addons Store _will_ only accept theme extensions.
> Later on we plan to add support for language packs and keyboard extensions.

## List of permissions FlorisBoard requests
Please refer to this [page](https://github.com/florisboard/florisboard/wiki/List-of-permissions-FlorisBoard-requests)
to get more information on this topic.

## Used libraries, components and icons
* [AndroidX libraries](https://github.com/androidx/androidx) by
  [Android Jetpack](https://github.com/androidx)
* [AboutLibraries](https://github.com/mikepenz/AboutLibraries) by
  [mikepenz](https://github.com/mikepenz)
* [Google Material icons](https://github.com/google/material-design-icons) by
  [Google](https://github.com/google)
* [JetPref preference library](https://github.com/patrickgold/jetpref) by
  [patrickgold](https://github.com/patrickgold)
* [KotlinX coroutines library](https://github.com/Kotlin/kotlinx.coroutines) by
  [Kotlin](https://github.com/Kotlin)
* [KotlinX serialization library](https://github.com/Kotlin/kotlinx.serialization) by
  [Kotlin](https://github.com/Kotlin)

Many thanks to [Nikolay Anzarov](https://www.behance.net/nikolayanzarov) ([@BloodRaven0](https://github.com/BloodRaven0)) for designing and providing the main app icons to this project!

## License
```
Copyright 2020-2025 The FlorisBoard Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

Thanks to [The FlorisBoard Contributors](https://github.com/florisboard/florisboard/graphs/contributors) for making this project possible!

<!-- BEGIN SECTION: obtainium_links -->
<!-- auto-generated link templates, do NOT edit by hand -->
<!-- see fastlane/update-readme.sh -->
[obtainium_preview]: https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://app/%7B%22id%22%3A%22dev.patrickgold.florisboard.beta%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fflorisboard%2Fflorisboard%22%2C%22author%22%3A%22florisboard%22%2C%22name%22%3A%22FlorisBoard%20Preview%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22preview%5C%22%7D%22%7D%0A
[obtainium_stable]: https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://app/%7B%22id%22%3A%22dev.patrickgold.florisboard%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fflorisboard%2Fflorisboard%22%2C%22author%22%3A%22florisboard%22%2C%22name%22%3A%22FlorisBoard%20Stable%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22stable%5C%22%7D%22%7D%0A
<!-- END SECTION: obtainium_links -->
