"""Prepare a screenshot reference and report a same-night, opt-in JVM experiment.

All personal inputs and generated reports stay under ignored app/build by default.
Screenshot coordinates/times must be supplied explicitly and visually checked.
The reference is used to score results, never passed into the sensor algorithm.
Its start/end are a fixed staging container; this is not candidate-onset validation.
"""
import argparse
import base64
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import itertools
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ZONE = timezone(timedelta(hours=8))
SELECTED = 'refine_h65_p45_x60_w1_b7_local30'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path, delimiter=','):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream, delimiter=delimiter))


def timestamp(text):
    return int(datetime.fromisoformat(text).replace(tzinfo=ZONE).timestamp() * 1000)


def local(ms):
    return datetime.fromtimestamp(int(ms) / 1000, ZONE)


def write_tsv(source, target):
    rows = read_csv(source)
    assert rows and all(None not in r for r in rows)
    with target.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter='\t', lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def prepare(args):
    from PIL import Image
    work = args.work.resolve()
    # The generator verifies the current source and keeps its SHA-256 in manifest.json.
    subprocess.run([sys.executable, str(ROOT / 'tools/offline-replay/prepare.py'), str(args.csv),
                    '--unsegmented', '--output', str(work)], check=True)
    for name in ['paired-summary.csv', 'paired-intervals.csv', 'paired-diagnostics.csv',
                 'paired-verification.txt', 'previous-night.txt', 'previous-intervals.csv',
                 'candidate-scenario.txt']:
        (work / name).unlink(missing_ok=True)
    write_tsv(args.previous_csv, work / 'previous.tsv')
    with Image.open(args.image) as image:
        left, right, y = args.plot_left, args.plot_right, args.scan_y
        assert 0 <= left < right <= image.width and 0 <= y < image.height
        image = image.convert('RGB')
        # Select the indigo Deep band at a row that does not intersect the blue Light band.
        pixels = [x for x in range(left, right) if
                  (lambda c: c[2] > 120 and c[2] > 1.8 * c[0] and c[2] > 1.8 * c[1])(image.getpixel((x, y)))]
        runs = []
        for _, group in itertools.groupby(enumerate(pixels), lambda p: p[1] - p[0]):
            xs = [p[1] for p in group]
            if len(xs) > 10:
                runs.append((xs[0], xs[-1] + 1))
        assert runs, 'No Deep band found; verify the scan row and screenshot'
        size = image.size
    start, end = timestamp(args.start), timestamp(args.end)
    assert start < end
    parts, cursor = [], start
    for a, b in runs:
        a = round(start + (a - left) / (right - left) * (end - start))
        b = round(start + (b - left) / (right - left) * (end - start))
        if a > cursor:
            parts.append((cursor, a, 'LIGHT'))
        parts.append((a, b, 'DEEP'))
        cursor = b
    if cursor < end:
        parts.append((cursor, end, 'LIGHT'))
    with (work / 'reference.tsv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter='\t', lineterminator='\n')
        writer.writerow(['start_ms', 'end_ms', 'stage'])
        writer.writerows(parts)
    metadata = dict(bounds=[left, right], scan_y=y, image_size=size, deep_pixel_spans=runs,
                    uncertainty_minutes=1, start_ms=start, end_ms=end,
                    source_sha256=sha(args.image), previous_csv_sha256=sha(args.previous_csv),
                    reference_tsv_sha256=sha(work / 'reference.tsv'), previous_tsv_sha256=sha(work / 'previous.tsv'),
                    selected_profile=SELECTED,
                    generated_at=datetime.now(timezone.utc).isoformat())
    (work / 'reference.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print(f'Prepared {len(runs)} screenshot Deep intervals. Run SameNightReplayTest before report.')


def report(args):
    work, output = args.work.resolve(), args.output.resolve()
    manifest = json.loads((work / 'manifest.json').read_text(encoding='utf-8'))
    assert sha(work / 'input.tsv') == manifest['input_tsv_sha256'], 'Input intermediate drift'
    for name, expected in manifest['source_sha256'].items():
        assert sha(ROOT / 'app/src/main/java/com/rsps1008/sleeptrace' / name) == expected, 'Production source drift'
    for name, expected in manifest['generated_sha256'].items():
        assert sha(work / 'generated' / name) == expected, 'Generated source drift'
    checks = work / 'paired-verification.txt'
    assert checks.exists() and 'controls PASS' in checks.read_text()
    test_dir = ROOT / 'app/build/test-results/testDebugUnitTest'
    required = ['SameNightReplayTest', 'AutomaticPlacementTest', 'SleepStageEstimatorTest',
                'TenHertzReviewRegressionTest', 'SparseCouplingNightReplayTest']
    tests = []
    for name in required:
        file = test_dir / f'TEST-com.rsps1008.sleeptrace.{name}.xml'
        root = ET.parse(file).getroot()
        assert not any(int(root.get(k, '0')) for k in ['failures', 'errors', 'skipped']), name
        assert file.stat().st_mtime >= (work / 'manifest.json').stat().st_mtime, 'Stale test report'
        tests.append(dict(name=name, tests=int(root.get('tests'))))
    summary = read_csv(work / 'paired-summary.csv')
    intervals = read_csv(work / 'paired-intervals.csv')
    reference = read_csv(work / 'reference.tsv', '\t')
    raw = read_csv(work / 'input.tsv', '\t')
    metadata = json.loads((work / 'reference.json').read_text(encoding='utf-8'))
    assert sha(work / 'reference.tsv') == metadata['reference_tsv_sha256'], 'Reference drift'
    assert sha(work / 'previous.tsv') == metadata['previous_tsv_sha256'], 'Previous-night input drift'
    selected = next(r for r in summary if r['id'] == SELECTED)
    baseline = next(r for r in summary if r['id'] == 'baseline')
    assert max(float(r['deep_f1']) for r in summary) == float(selected['deep_f1'])
    assert (float(selected['deep_min']), int(selected['bouts'])) == (97.0, 5), 'Review selected profile after source/input changes'
    output.mkdir(parents=True, exist_ok=True)
    chosen_intervals = [r for r in intervals if r['profile'] in ['baseline', SELECTED]]
    reference_deep = [r for r in reference if r['stage'] == 'DEEP']
    reference_minutes = sum((int(r['end_ms']) - int(r['start_ms'])) / 60000 for r in reference_deep)

    # Static scientific comparison: independent plots, one common clock axis.
    sys.path.insert(0, str(ROOT / 'app/build/offline-replay/python-deps'))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.dates as dates
    from matplotlib.patches import Patch
    plt.rcParams['font.family'] = ['Microsoft JhengHei', 'DejaVu Sans']
    colors = {'LIGHT': '#3984ef', 'DEEP': '#332aba', 'AWAKE': '#e59646'}
    first_complete = next(timestamp(r['timestamp_local']) for r in raw if float(r['covered_seconds']) >= 45)
    phone_start = min(timestamp(r['timestamp_local']) for r in raw if 'PHONE_IN_USE' in r['coupling_current_blocker'])
    fig, axes = plt.subplots(3, 1, figsize=(15, 7.4), sharex=True)
    figures = [(reference, f'小米參考　約 {reference_minutes:.0f} 分鐘深睡／5 段'),
               ([r for r in chosen_intervals if r['profile'] == 'baseline'], '規則 12 條件回放　61 分鐘深睡／2 段'),
               ([r for r in chosen_intervals if r['profile'] == SELECTED], '調整後實驗設定　97 分鐘深睡／5 段')]
    for index, (ax, (parts, title)) in enumerate(zip(axes, figures)):
        for r in parts:
            a, b = dates.date2num(local(r['start_ms'])), dates.date2num(local(r['end_ms']))
            level = 0.12 if r['stage'] == 'DEEP' else 0.75
            if r['stage'] == 'AWAKE':
                level = 1.25
            ax.broken_barh([(a, b-a)], (level, .36), facecolors=colors[r['stage']])
        if index:
            ax.axvspan(local(metadata['start_ms']), local(first_complete), color='#c5cad5', alpha=.9)
            ax.axvline(local(phone_start), color='#d9852d', linestyle=':', linewidth=1.5)
        ax.set_title(title, loc='left', fontsize=12, pad=8)
        ax.set_ylim(0, 1.68)
        ax.set_yticks([.3,.93,1.43], ['深睡','淺睡','使用'])
        ax.grid(axis='x', color='#e0e4ed', linewidth=.7)
        ax.spines[['top','right','left']].set_visible(False)
        ax.tick_params(axis='y', length=0)
    axes[-1].xaxis.set_major_locator(dates.MinuteLocator(byminute=[0,30], tz=ZONE))
    axes[-1].xaxis.set_major_formatter(dates.DateFormatter('%H:%M', tz=ZONE))
    axes[-1].set_xlim(local(metadata['start_ms']), local(metadata['end_ms']))
    fig.suptitle('同一晚的深淺睡眠比較', x=.055, ha='left', fontsize=19)
    fig.text(.055,.926,'固定參考起訖 02:28–09:28；圖像邊界約有 ±1 分鐘讀圖誤差',fontsize=10,color='#526075')
    fig.legend(handles=[Patch(color='#c5cad5',label='無完整感測摘要'),Patch(color=colors['LIGHT'],label='Light'),
                        Patch(color=colors['DEEP'],label='Deep'),Patch(color=colors['AWAKE'],label='手機使用排除')],
               loc='lower left',bbox_to_anchor=(.05,.015),ncol=4,frameon=False,fontsize=10)
    fig.text(.055,.075,'一致率只計 02:36–09:24 的 408 分鐘。此圖不驗證自動入睡／醒來時間，也不是醫療準確率。',fontsize=10,color='#526075')
    fig.subplots_adjust(left=.055,right=.985,top=.86,bottom=.16,hspace=.55)
    fig.savefig(output / 'comparison.png', dpi=160, facecolor='white')
    plt.close(fig)

    def span(r):
        return f"{local(r['start_ms']):%H:%M}–{local(r['end_ms']):%H:%M}"

    candidate_deep = [r for r in chosen_intervals if r['profile'] == SELECTED and r['stage'] == 'DEEP']
    rows_html = ''.join(f'<tr><td>{span(a)}</td><td>{span(b)}</td></tr>' for a,b in zip(reference_deep,candidate_deep))
    sensitivity_ids = [f'refine_h{n}_p45_x60_w1_b7_local30' for n in [55,60,65,70]]
    sensitivity = [next(r for r in summary if r['id'] == key) for key in sensitivity_ids]
    sensitivity_html = ''.join(f"<tr><td>{r['hold']} 分鐘</td><td>{float(r['deep_min']):.0f}</td><td>{r['bouts']}</td><td>{100*float(r['deep_f1']):.1f}%</td></tr>" for r in sensitivity)
    total_tests = sum(r['tests'] for r in tests)
    previous = (work / 'previous-night.txt').read_text()
    image64 = base64.b64encode((output / 'comparison.png').read_bytes()).decode()
    page = f'''<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><title>SleepTrace 同晚比較</title>
<style>body{{font:16px/1.75 "Microsoft JhengHei",sans-serif;color:#243049;background:#f3f5fa;margin:0}}main{{max-width:1120px;margin:32px auto;padding:38px;background:white;border-radius:18px}}h1{{margin:0 0 8px;font-size:30px}}h2{{font-size:21px;margin-top:30px}}p{{max-width:1000px}}img{{width:100%;height:auto}}table{{border-collapse:collapse;width:100%;margin:18px 0}}th,td{{text-align:left;padding:10px 14px;border-bottom:1px solid #dce2ed}}th{{background:#edf1f9}}.note{{color:#59657b}}.lead{{font-size:19px}}code{{background:#edf1f9;padding:2px 5px}}a{{color:#255ba8}}</style><main>
<h1>SleepTrace 與小米同晚比較</h1><p class="note">2026-10-03，Asia/Taipei。條件式離線回放，正式 App 維持規則 12。</p>
<p class="lead">1,586 組設定中，選定實驗設定得到 <b>97 分鐘深睡、5 段</b>；小米讀圖約 <b>{reference_minutes:.0f} 分鐘、5 段</b>。總量接近，段落位置仍有明顯落差。</p>
<img src="data:image/png;base64,{image64}" alt="小米、規則12與實驗設定的共用時間軸">
<h2>比較數字的範圍</h2><p>CSV 含 475 個分鐘摘要，02:35–10:29，全部為 feature v7。沒有 session、分期、Google 原始分類或精確 UsageStats 區間。因此以小米起訖建立已接受睡眠的測試容器，僅比較分期；沒有把小米的深睡標籤送入演算法。</p>
<p>02:28–02:35 沒有摘要，02:35 為部分分鐘；09:24 起的 PHONE_IN_USE 已含 ±2 分鐘耦合防護，測試保守排除整個標記範圍。指標使用剩餘 408 個完整感測分鐘，以每分鐘中點比對讀圖標記。讀圖深睡為約 102.6 分鐘，映射到分鐘格後是 104 格。</p>
<table><tr><th>指標</th><th>規則 12 條件回放</th><th>調整後實驗</th></tr>
<tr><td>深睡分鐘／段數</td><td>61／2</td><td>97／5</td></tr>
<tr><td>最長深睡段</td><td>36 分鐘</td><td>32 分鐘（小米約 46 分鐘）</td></tr>
<tr><td>深淺標記一致率</td><td>{100*float(baseline['stage_agreement']):.1f}%</td><td>{100*float(selected['stage_agreement']):.1f}%</td></tr>
<tr><td>Deep F1</td><td>{100*float(baseline['deep_f1']):.1f}%</td><td>{100*float(selected['deep_f1']):.1f}%</td></tr>
<tr><td>與小米重疊的深睡分鐘格</td><td>13</td><td>61</td></tr>
<tr><td>小米為淺睡、實驗為深睡</td><td>48 格</td><td>36 格</td></tr>
<tr><td>小米為深睡、實驗未抓到</td><td>91 格</td><td>43 格</td></tr></table>
<p class="note">這是使用同一晚選參數後的樣本內吻合度，不是未見資料的驗證準確率。Light 佔多數，因此不能只看一致率；Deep F1 同時考慮多判與漏判。</p>
<h2>逐段結果</h2><p>以下兩欄各自按時間排序，列號不代表段落已配對。</p>
<table><tr><th>小米讀圖深睡（約）</th><th>實驗深睡</th></tr>{rows_html}</table>
<p>小米第一段在實驗中被拆成兩段；06:22–06:43 是額外判出的深睡。07:09–07:17 有重疊；08:08–08:34 合併跨過小米中間的淺睡。最後約 08:55–09:12 的小米深睡仍未抓到。</p>
<h2>調整內容與敏感性</h2><p>保留 3 次合格動作建立耦合，支持期限 45→65 分鐘。Deep 入口窗口 15→10 分鐘；進出門檻改以最近 30 分鐘符合條件的 BED 摘要之 P45／P60，退出確認 3→1，最短保留段 10→7 分鐘。局部資料不足時沿用全晚基準；全晚品質與分期資格仍保留。沒有固定週期、目標比例或指定深睡時刻。</p>
<table><tr><th>只改支持期限</th><th>Deep 分鐘</th><th>段數</th><th>Deep F1</th></tr>{sensitivity_html}</table>
<p>只增加 5 分鐘支持期限便出現額外 40 分鐘 Deep，顯示目前對單晚訊號敏感。正式規則與 APK 沒有套用此實驗設定。</p>
<h2>測試與保留限制</h2><p>{total_tests} 項相關 JVM tests 通過，失敗／錯誤／略過均為 0。全部 1,586 組均檢查時間守恆、不可在無資格時判 Deep、最短段、完全靜止、整段手機使用、錄製切換、3 秒缺口與沒有 Google 睡眠證據的反例。</p>
<p>前一晚的正式逐段輸出完整重現；同組實驗設定得到 37 分鐘／3 段、最長 14 分鐘，原規則為 31 分鐘／1 段。該晚沒有同晚小米標記，這只證明可回放與未產生整晚 Deep，不能證明準確度改善。</p>
<p>另測試「第一個感測事件時恰有高信心分類」的假設，可得到約 02:35:27–09:24 的候選；第一個感測事件時間不是實際分類時間，09:24 也包含保守的使用邊界，不能視為已重現 App 的自動睡眠起訖。完全沒有 Google 證據時仍不生成有效候選。</p>
<p>沒有操作手機、DB 或 Health Connect，沒有安裝／發布 APK；小米參考與手機動作摘要均不是生理真值。</p>
<p><a href="selected-profile.json">實驗設定與來源雜湊</a> · <a href="comparison.csv">全部設定比較</a> · <a href="selected-intervals.csv">參考及選定分段</a> · <a href="verification.txt">測試摘要</a></p></main></html>'''
    (output / 'report.html').write_text(page, encoding='utf-8')
    with (output / 'selected-intervals.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=['profile','start_ms','end_ms','stage'])
        writer.writeheader()
        writer.writerows([dict(profile='xiaomi_image', **r) for r in reference] + chosen_intervals)
    (output / 'selected-profile.json').write_text(json.dumps(dict(selected=selected, reference=metadata,
        source=manifest, tests=tests, experimental_only=True, production_algorithm=12,
        scoring='408 observed minute midpoints; same-night fitting, not held-out validation'), indent=2), encoding='utf-8')
    shutil.copy2(work / 'paired-summary.csv', output / 'comparison.csv')
    (output / 'verification.txt').write_text(checks.read_text() + previous + '\n' +
        json.dumps(tests, indent=2) + f'\n{total_tests} tests passed. Production source SHA-256 unchanged.\n', encoding='utf-8')
    print(f'Report: {output / "report.html"}; {len(summary)} profiles, {total_tests} tests')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['prepare','report'])
    parser.add_argument('--work', type=Path, default=ROOT / 'app/build/offline-replay/same-night')
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build/reports/same-night-2026-10-03')
    parser.add_argument('--csv', type=Path)
    parser.add_argument('--previous-csv', type=Path)
    parser.add_argument('--image', type=Path)
    parser.add_argument('--start')
    parser.add_argument('--end')
    parser.add_argument('--plot-left', type=int)
    parser.add_argument('--plot-right', type=int)
    parser.add_argument('--scan-y', type=int)
    args = parser.parse_args()
    if args.mode == 'prepare':
        for key in ['csv','previous_csv','image','start','end','plot_left','plot_right','scan_y']:
            if getattr(args,key) is None:
                parser.error(f'prepare requires --{key.replace("_","-")}')
        prepare(args)
    else:
        report(args)
