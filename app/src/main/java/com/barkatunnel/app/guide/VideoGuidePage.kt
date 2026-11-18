package com.barkatunnel.app.guide

/** No native JavaScript bridge is exposed to the embedded third-party player. */
internal object VideoGuidePage {
    fun origin(packageName: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        return "https://$packageName"
    }

    fun html(id: String, origin: String, attempt: Int): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        require(origin.matches(Regex("https://[A-Za-z0-9_.]+")))
        require(attempt > 0)
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body,iframe,#player{width:100%;height:100%;margin:0;border:0;background:#000}</style>
            </head><body><div id="player"></div><script>
            var guidePlayer;
            function signal(value) { document.title = 'guide-$attempt-' + value; }
            function onYouTubeIframeAPIReady() {
                guidePlayer = new YT.Player('player', {
                    videoId: '$id', width: '100%', height: '100%',
                    playerVars: { playsinline: 1, origin: '$origin' },
                    events: {
                        onReady: function () { signal('ready'); },
                        onStateChange: function (event) {
                            if (event.data === 1) signal('playing');
                            else if (event.data === 3) signal('buffering');
                            else if (event.data === 2 || event.data === 0) signal('ready');
                        },
                        onError: function (event) { signal('error-' + event.data); }
                    }
                });
            }
            function pauseGuide() { if (guidePlayer && guidePlayer.pauseVideo) guidePlayer.pauseVideo(); }
            </script><script src="https://www.youtube.com/iframe_api" onerror="signal('network')"></script>
            </body></html>
        """.trimIndent()
    }
}
