/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

"use strict";

/* import-globals-from head-multiple.js */

Services.scriptloader.loadSubScript(
  "chrome://mochitests/content/browser/ipc/glue/test/browser/head-multiple.js",
  this
);

add_setup(async function checkAudioDecodingNonUtility() {
  const isAudioDecodingNonUtilityAllowed = await SpecialPowers.getBoolPref(
    "media.allow-audio-non-utility"
  );
  ok(
    !isAudioDecodingNonUtilityAllowed,
    "Audio decoding should not be allowed on non utility processes by default"
  );
});

add_task(async function testAudioDecodingInUtility() {
  await runTest({ expectUtility: true });
});
