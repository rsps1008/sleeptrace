"""Summarize verified JVM replays and plot different nights on a normalized axis.

Needs matplotlib; install only in app/build/offline-replay/python-deps if absent.
The Xiaomi screenshot is read for approximate visual morphology, never used by the JVM.
"""
import argparse
import csv
import datetime as dt
import hashlib
import json
from pathlib import Path
import shutil
import statistics
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'app/build/offline-replay'
DEST = ROOT / 'docs/offline-replay-2026-10-02'
sys.path.insert(0, str(BUILD / 'python-deps'))
from PIL import Image
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
from matplotlib.patches import Patch

TZ = dt.timezone(dt.timedelta(hours=8))


def hm(ms):
    return dt.datetime.fromtimestamp(ms/1000, TZ).strftime('%H:%M')


def load(name):
    return list(csv.DictReader((BUILD/name).open(encoding='utf-8')))


def main():
    global BUILD, DEST
    parser = argparse.ArgumentParser()
    parser.add_argument('reference', type=Path)
    parser.add_argument('--round', type=int, choices=(1,2), default=1)
    args = parser.parse_args()
    if args.round==2:
        BUILD=BUILD/'round2'
        DEST=ROOT/'docs/offline-replay-2026-10-02-round2'
    rows = load('summary.csv')
    intervals = load('intervals.csv')
    by_id = {r['id']: r for r in rows}
    baseline = [r for r in intervals if r['profile']=='baseline']
    start = min(int(r['start_ms']) for r in baseline)
    end = max(int(r['end_ms']) for r in baseline)
    span = (end-start)/60000
    config_keys = list(rows[0])[2:list(rows[0]).index('deep_min')]
    unique = len({tuple(r[k] for k in config_keys) for r in rows})
    assert (BUILD/'verification.txt').exists()
    manifest = json.loads((BUILD/'manifest.json').read_text(encoding='utf-8'))
    for rel, expected in manifest['source_sha256'].items():
        actual = hashlib.sha256((ROOT/'app/src/main/java/com/rsps1008/sleeptrace'/rel).read_bytes()).hexdigest()
        assert actual==expected, f'Production source changed: {rel}'

    # Fixed inspected screenshot geometry. This deliberately fails on a different image.
    ref = Image.open(args.reference).convert('RGB')
    assert ref.size == (868, 1886), 'Reinspect ROI before using a different screenshot'
    bounds = (48, 819)
    def segments_at(y):
        segments=[]
        begin=None
        for x in range(*bounds):
            r,g,b=ref.getpixel((x,y))
            on = b>150 and b>r+15 and r>90
            if on and begin is None: begin=x
            if not on and begin is not None:
                segments.append((begin,x)); begin=None
        if begin is not None: segments.append((begin,bounds[1]))
        return segments
    pixels = segments_at(1648)
    assert len(pixels)==8 and all(b-a>5 for a,b in pixels)
    assert len(segments_at(1646))==8 and len(segments_at(1650))==8
    width=bounds[1]-bounds[0]
    ref_parts=[((a-bounds[0])/width,(b-bounds[0])/width) for a,b in pixels]
    ref_span=466.0  # screenshot's displayed 7h46; bedtime/wake labels are minute-rounded
    ref_lengths=[(b-a)*ref_span for a,b in ref_parts]
    ref_metrics=dict(
        source_sha256=hashlib.sha256(args.reference.read_bytes()).hexdigest(),
        source_size=list(ref.size), track_bounds_px=list(bounds), deep_scanline_y=1648,
        deep_intervals_px=pixels, deep_intervals_normalized=ref_parts,
        displayed_sleep_minutes=ref_span, approximate_deep_minutes=sum(ref_lengths),
        deep_percent=100*sum(ref_lengths)/ref_span, bouts=8,
        median_bout_minutes=statistics.median(ref_lengths), longest_bout_minutes=max(ref_lengths),
        bouts_per_hour=8/(ref_span/60),
        first_half_deep_minutes=sum(max(0,min(b,.5)-a)*ref_span for a,b in ref_parts),
        uncertainty='Screenshot pixel estimate, about 1-2 pixels per boundary; unknown night. Not wearable/PSG labels or exact stage times.'
    )
    selected = [
        ('baseline','現行規則'),
        ('noise2.5','只降動作底噪倍數 3 → 2.5'),
        ('grid_n2_noise3.0_peak1.5_h45_w15_p65_x70','候選 A：2 個動作＋入口 P65'),
        ('grid_n2_noise3.0_peak3.0_h45_w15_p65_x65','較寬設定：峰值 3.0＋退出 P65'),
        ('focused_peak_b5_x50_e1_w10','8 段壓力測試：敏感退出＋短段保留'),
    ]
    if args.round==2:
        selected=[('anchor_A','前輪 A：2 個動作＋入口 P65'),
            ('anchor_B','前輪 B：敏感退出的 8 段設定'),
            ('B_peak2.5_x65_w15_e2_b5_r5','新 C：8 段，縮短中間長段'),
            ('C_peak2.5_x67_w14_e2_b6','新 D：7 段，最短段 6 分鐘'),
            ('B_peak2.5_x60_w15_e2_b5_r5','新 F：8 段，退出門檻 P60')]
    metrics=[]
    for row in rows:
        parts=[r for r in intervals if r['profile']==row['id'] and r['stage']=='DEEP']
        lengths=[(int(r['end_ms'])-int(r['start_ms']))/60000 for r in parts]
        metrics.append({**row, 'deep_percent':float(row['deep_min'])/span*100,
            'bouts_per_hour':len(parts)/(span/60),
            'median_bout_minutes':statistics.median(lengths) if lengths else 0,
            'longest_bout_minutes':max(lengths,default=0),
            'short_bouts_le5':sum(x<=5 for x in lengths),
            'light_gaps_le2':sum((int(b['start_ms'])-int(a['end_ms']))<=120000 for a,b in zip(parts,parts[1:])),
            'first_deep_elapsed_percent':(int(parts[0]['start_ms'])-start)/(end-start)*100 if parts else None,
            'first_half_share_of_deep':float(row['first_half_deep_min'])/float(row['deep_min']) if float(row['deep_min']) else 0,
            'deep_intervals':'; '.join(f'{hm(int(r["start_ms"]))}-{hm(int(r["end_ms"]))}' for r in parts)})
    DEST.mkdir(parents=True,exist_ok=True)
    with (DEST/'comparison.csv').open('w',encoding='utf-8-sig',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=metrics[0].keys())
        writer.writeheader();writer.writerows(metrics)
    for name in ['intervals.csv','transitions.csv','manifest.json','verification.txt']:
        shutil.copyfile(BUILD/name,DEST/name)
    (DEST/'xiaomi-reference.json').write_text(json.dumps(ref_metrics,ensure_ascii=False,indent=2),encoding='utf-8')
    if args.round==2:
        selected_ids={x[0] for x in selected} | {'C_peak2.5_x70_w14_e2_b6'}
        events=load('transitions.csv')
        traced=[]
        for profile in sorted(selected_ids):
            for part in [r for r in intervals if r['profile']==profile and r['stage']=='DEEP']:
                e=next((r for r in events if r['profile']==profile and r['start_ms']==part['end_ms'] and r['action']=='EXIT'),{})
                traced.append(dict(profile=profile,start=hm(int(part['start_ms'])),end=hm(int(part['end_ms'])),
                    duration_min=(int(part['end_ms'])-int(part['start_ms']))/60000,
                    exit_reason=e.get('reason','session_end'),primary_reason=e.get('primary_reason',''),
                    coupling_reason=e.get('coupling_reason','')))
        with (DEST/'selected-intervals.csv').open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.DictWriter(f,fieldnames=traced[0].keys());w.writeheader();w.writerows(traced)
        with (DEST/'anchor-one-factor.csv').open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.DictWriter(f,fieldnames=metrics[0].keys());w.writeheader();w.writerows(r for r in metrics if r['group'] in ('anchor','local'))
        (DEST/'selected-profiles.json').write_text(json.dumps([r for r in metrics if r['id'] in selected_ids],ensure_ascii=False,indent=2),encoding='utf-8')

    font=FontProperties(fname=r'C:\Windows\Fonts\msjh.ttc')
    plt.rcParams['font.family']=font.get_name()
    plt.rcParams['axes.unicode_minus']=False
    purple='#8664bb'; light='#b5c5de'; gray='#e8eaee'; ink='#24344a'
    fig, ax=plt.subplots(figsize=(13.5,8.5 if args.round==2 else 7.5),dpi=170)
    fig.patch.set_facecolor('#ffffff')
    fig.suptitle('第二輪回放：保留前段、縮短長段、比較分段密度' if args.round==2 else '相同夜間資料：門檻改動如何改變深睡分段',x=.035,y=.965,ha='left',fontsize=20,color=ink)
    fig.text(.035,.915,'上軌淺睡、下軌深睡。小米是不同晚；各列按自己的睡眠長度縮放，不能逐分鐘對齊。' if args.round==2 else '小米為另一天的舊圖；各列按自己的睡眠長度縮放，不能逐分鐘對齊。紫色為各方法的深睡輸出。',fontsize=11,color='#516173')
    lanes=[('小米舊圖（像素估讀）',ref_parts,'約 8 段｜約 120 分／466 分｜約 26%')]
    for ident,label in selected:
        row=by_id[ident]
        parts=[((int(r['start_ms'])-start)/(end-start),(int(r['end_ms'])-start)/(end-start)) for r in intervals if r['profile']==ident and r['stage']=='DEEP']
        lanes.append((label,parts,f'{row["deep_bouts"]} 段｜{float(row["deep_min"]):.0f} 分／342 分｜{float(row["deep_min"])/span*100:.1f}%'))
    count=len(lanes)
    for i,(label,parts,stat) in enumerate(lanes):
        y=count-i-1
        if args.round==2:
            cursor=0
            for a,b in parts:
                if a>cursor:ax.broken_barh([(cursor*100,(a-cursor)*100)],(y+.02,.12),facecolors=light,edgecolors='none')
                ax.broken_barh([(a*100,(b-a)*100)],(y-.20,.12),facecolors=purple,edgecolors='none')
                ax.vlines([a*100,b*100],y-.14,y+.08,color='#b9bdc7',linestyles='dotted',linewidth=.8)
                cursor=b
            if cursor<1:ax.broken_barh([(cursor*100,(1-cursor)*100)],(y+.02,.12),facecolors=light,edgecolors='none')
        else:
            ax.broken_barh([(0,100)],(y-.12,.24),facecolors=gray if i==0 else light,edgecolors='none')
            ax.broken_barh([(a*100,(b-a)*100) for a,b in parts],(y-.12,.24),facecolors=purple,edgecolors='none')
        ax.text(0,y+.28,label,fontsize=11,color=ink,va='bottom')
        ax.text(100,y+.28,stat,fontsize=10,color=ink,ha='right',va='bottom')
    ax.axvline(50,color='#b7bcc4',linewidth=.8,zorder=0)
    ax.set_xlim(0,100);ax.set_ylim(-.5,count-.1)
    ax.set_yticks([]);ax.set_xticks([0,25,50,75,100],['入睡 0%','25%','50%','75%','醒來 100%'])
    ax.tick_params(axis='x',labelsize=11,colors=ink)
    ax.set_xlabel('各列睡眠紀錄的相對位置（%）',fontsize=12,labelpad=12,color=ink)
    for side in ['left','right','top']:ax.spines[side].set_visible(False)
    ax.spines['bottom'].set_color('#bcc2ca')
    fig.text(.035,.035,'今晚固定範圍 01:30:29–07:12:29。藍色包括證據不足的淺睡回退；圖形相似不代表睡眠分期準確。',fontsize=11,color='#516173')
    fig.subplots_adjust(left=.045,right=.97,top=.855,bottom=.18)
    fig.savefig(DEST/'threshold-comparison.png',facecolor=fig.get_facecolor())
    plt.close(fig)

    tests=[]
    for file in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite=ET.parse(file).getroot()
        tests.append(dict(name=suite.attrib['name'], tests=int(suite.attrib['tests']),
            failures=int(suite.attrib['failures']),errors=int(suite.attrib['errors']),skipped=int(suite.attrib['skipped'])))
    (DEST/'test-results.json').write_text(json.dumps(tests,indent=2),encoding='utf-8')
    print(json.dumps({'profiles':len(rows),'unique_configs':unique,'reference':ref_metrics,
        'selected':[r for r in metrics if r['id'] in [x[0] for x in selected]],'tests':tests},ensure_ascii=False,indent=2))


if __name__=='__main__':main()
