"""Generate test-only variants from current source; never edit the release algorithm.

Run with the bundled Python, then the init-script Gradle command in README.md.
CSV inputs remain in the ignored build directory. Every source replacement is guarded.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'app/src/main/java/com/rsps1008/sleeptrace'
OUT = ROOT / 'app/build/offline-replay'


def replace_once(text, old, new):
    assert text.count(old) == 1, f'Source drift: {old!r}'
    return text.replace(old, new)


def main():
    global OUT
    parser = argparse.ArgumentParser()
    parser.add_argument('csv', type=Path)
    parser.add_argument('--round', type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    if args.round == 2:
        OUT = OUT / 'round2'
    source = args.csv.read_bytes()
    rows = list(csv.DictReader(source.decode('utf-8-sig').splitlines()))
    assert rows and all(None not in r for r in rows)
    assert len({r['timestamp_local'] for r in rows}) == len(rows)
    ids = {r['session_id'] for r in rows if r['session_id']}
    assert len(ids) == 1, 'This fixed-session experiment requires exactly one exported session'
    assert {r['feature_version'] for r in rows} == {'7'}, 'This experiment is for the v7 export'
    OUT.mkdir(parents=True, exist_ok=True)
    # A failed subsequent run must not leave an old PASS available to summarize.
    for filename in ['summary.csv', 'intervals.csv', 'transitions.csv', 'verification.txt']:
        (OUT / filename).unlink(missing_ok=True)
    # A TSV intermediate avoids embedding sensitive paths/identifiers in tracked code.
    with (OUT / 'input.tsv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys(), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
    generated = OUT / 'generated'
    generated.mkdir(exist_ok=True)
    files = {name: (BASE / name).read_text(encoding='utf-8') for name in [
        'motion/SignalCoupling.kt', 'motion/AutomaticPlacement.kt', 'sleep/SleepStageEstimator.kt']}
    policy = 'package com.rsps1008.sleeptrace.motion\n\n' + files['motion/SignalCoupling.kt'].split('object CouplingPolicy {', 1)[1]
    policy = policy.replace('package com.rsps1008.sleeptrace.motion\n\n', 'package com.rsps1008.sleeptrace.motion\n\nobject OfflineCouplingPolicy {\n', 1)
    policy = policy.replace('const val ', 'var ')
    policy = policy.rstrip()[:-1] + '''
    var PEAK_HANDLING_DELTA = 1.5
    var COMPOSITE_PEAK_RESET = false
    var RENEW_NOISE_MULTIPLIER = -1.0
}
'''
    placement = files['motion/AutomaticPlacement.kt'].replace('AutomaticPlacement', 'OfflineAutomaticPlacement').replace('CouplingPolicy', 'OfflineCouplingPolicy')
    placement = replace_once(placement, 'minimumSpanMillis: Long\n', 'minimumSpanMillis: Long,\n        noiseMultiplier: Double = OfflineCouplingPolicy.NOISE_MULTIPLIER\n')
    placement = replace_once(placement, 'noise * OfflineCouplingPolicy.NOISE_MULTIPLIER', 'noise * noiseMultiplier')
    placement = replace_once(placement,
        '(minute.maxDelta ?: 0.0) > OfflineCouplingPolicy.HANDLING_DELTA ||',
        '''((minute.maxDelta ?: 0.0) > OfflineCouplingPolicy.PEAK_HANDLING_DELTA &&
                    (!OfflineCouplingPolicy.COMPOSITE_PEAK_RESET ||
                        minute.activeMillis >= 8_000L || (minute.postureDelta ?: 0.0) >= 0.5)) ||''')
    placement = replace_once(placement,
        '.any { it.startMillis == minute.startMillis }\n                val supportStillValid',
        '''.any { it.startMillis == minute.startMillis } ||
                    (supportedAt != null && OfflineCouplingPolicy.RENEW_NOISE_MULTIPLIER > 0 &&
                        listOf(fastHistory, history).any { candidateHistory ->
                            evaluateWindow(candidateHistory, 0, 0L, OfflineCouplingPolicy.RENEW_NOISE_MULTIPLIER)
                                .movements.any { it.startMillis == minute.startMillis }
                        })
                val supportStillValid''')
    estimator = files['sleep/SleepStageEstimator.kt'].replace('SleepStageEstimator', 'OfflineSleepStageEstimator')
    for old, new in [
        ('const val DEEP_WINDOW_MINUTES = 15', 'var DEEP_WINDOW_MINUTES = 15'),
        ('private const val TEN_HZ_MIN_DEEP_RUN_MILLIS = 10 * MINUTE_MS', 'var TEN_HZ_MIN_DEEP_RUN_MILLIS = 10 * MINUTE_MS'),
        ('private const val EXIT_DENSE_EVENTS = 8', 'var EXIT_DENSE_EVENTS = 8'),
        ('(features.medianRms ?: Double.MAX_VALUE) > baseline.p50', '(features.medianRms ?: Double.MAX_VALUE) > entryThreshold(baseline)'),
        ('val highCandidate = if (short.validMinutes == 5 && (short.medianRms ?: Double.MAX_VALUE) > baseline!!.p70)',
         'val exitRmsWindow = rollingFeatures(timeline, maxOf(0, index - EXIT_RMS_LOOKBACK_MINUTES + 1)..index)\n            val highCandidate = if (exitRmsWindow.validMinutes == EXIT_RMS_LOOKBACK_MINUTES && (exitRmsWindow.medianRms ?: Double.MAX_VALUE) > exitThreshold(baseline!!))'),
        ('if (highCandidate >= 3)', 'if (highCandidate >= EXIT_HIGH_WINDOWS)'),
    ]:
        estimator = replace_once(estimator, old, new)
    estimator = replace_once(estimator, 'object OfflineSleepStageEstimator {', '''object OfflineSleepStageEstimator {
    var ENTRY_PERCENTILE = 50
    var EXIT_PERCENTILE = 70
    var EXIT_HIGH_WINDOWS = 3
    var EXIT_RMS_LOOKBACK_MINUTES = 5
    private fun entryThreshold(b: NightlyBaseline): Double = b.experimentalEntryRms
    private fun exitThreshold(b: NightlyBaseline): Double = b.experimentalExitRms
''')
    estimator = replace_once(estimator,
        'val sampleCount: Int = bedMinutes,\n        val reason: String? = null',
        'val sampleCount: Int = bedMinutes,\n        val experimentalEntryRms: Double = p50,\n        val experimentalExitRms: Double = p70,\n        val reason: String? = null')
    estimator = replace_once(estimator,
        'p50 * MIN_RELATIVE_SIGNAL_RANGE), featureVersion = version)',
        'p50 * MIN_RELATIVE_SIGNAL_RANGE), featureVersion = version,\n            experimentalEntryRms = percentileSorted(sorted, ENTRY_PERCENTILE / 100.0),\n            experimentalExitRms = percentileSorted(sorted, EXIT_PERCENTILE / 100.0))')
    for filename, content in [('OfflineCouplingPolicy.kt', policy), ('OfflineAutomaticPlacement.kt', placement), ('OfflineSleepStageEstimator.kt', estimator)]:
        (generated / filename).write_text(content, encoding='utf-8')
    manifest = {
        'round': args.round,
        'input_sha256': hashlib.sha256(source).hexdigest(), 'rows': len(rows), 'columns': len(rows[0]),
        'source_sha256': {name: hashlib.sha256((BASE / name).read_bytes()).hexdigest() for name in files},
        'generated_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(generated.glob('*.kt'))},
        'scope': 'Fixed exported session boundaries; accepted sleep evidence reconstructed from CSV; no candidate selection, classification delivery, storage or Health Connect replay.',
        'precision': 'RMS and other floating features are rounded to 6 decimals in the export; baseline equivalence is required before sweep.'
    }
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(f'Prepared {len(rows)} rows; generated test-only sources in {generated}')


if __name__ == '__main__':
    main()
