#!/usr/bin/env python3
"""The comparison of #67: four runs (before, after, before, after) of RebuildBenchmark and
ProjectionBenchmark, a least-squares fit of log time on the run's slot (the drift of the machine
over the night) and on whether it is the "after" code, per case. Usage, from the repository root:
    python3 benchmarks/multipattern-ab/fit.py benchmarks/multipattern-ab
"""
import sys,math,re,statistics as st
def reb(p):
    d={}
    for l in open(p):
        t=l.split()
        if 'RebuildBenchmark.' in l and ('±' in t or '+-' in t):
            i=t.index('±') if '±' in t else t.index('+-'); d[' '.join(t[:i-3])]=float(t[i-1])
    return d
def prj(p):
    sec='';d={}
    for l in open(p):
        if l.startswith('Warm'): sec='warm '+('compact' if 'Compact' in l else 'default')
        elif l.startswith('Cold'): sec='cold '+('compact' if 'Compact' in l else 'default')
        m=re.match(r'^ProjectionBenchmark\.(\S+)\s+(\d+)\s+(avgt|ss)\s+\d+\s+([\d.]+)',l.strip())
        if m and not any(x in m.group(1) for x in ('gc.count','gc.time','gc.alloc.rate:')): d[(sec,m.group(1),m.group(2))]=float(m.group(4))
    return d
runs=['run1-before','run2-after','run3-before','run4-after']
base=sys.argv[1]
R=[reb(f'{base}/{r}/RebuildBenchmark-jdk25-results.txt') for r in runs]
P=[prj(f'{base}/{r}/ProjectionBenchmark-jdk25-results.txt') for r in runs]
def fit(vals):
    # log t_i = a + d*i + e*after_i, i=0..3, after=[0,1,0,1]; least squares for e
    y=[math.log(v) for v in vals]; i=[0,1,2,3]; z=[0,1,0,1]
    # solve 3x3 normal equations
    import itertools
    X=[[1,i[k],z[k]] for k in range(4)]
    A=[[sum(X[k][r]*X[k][c] for k in range(4)) for c in range(3)] for r in range(3)]
    b=[sum(X[k][r]*y[k] for k in range(4)) for r in range(3)]
    # gauss
    n=3
    for c in range(n):
        p=max(range(c,n),key=lambda r:abs(A[r][c])); A[c],A[p]=A[p],A[c]; b[c],b[p]=b[p],b[c]
        for r in range(c+1,n):
            f=A[r][c]/A[c][c]
            for k in range(c,n): A[r][k]-=f*A[c][k]
            b[r]-=f*b[c]
    x=[0]*n
    for r in reversed(range(n)):
        x[r]=(b[r]-sum(A[r][k]*x[k] for k in range(r+1,n)))/A[r][r]
    return math.exp(x[1]),math.exp(x[2])   # drift per slot, after/before effect
keys=[k for k in R[0] if all(k in r for r in R)]
def gm(v): return math.exp(sum(map(math.log,v))/len(v))
eff=[fit([R[j][k] for j in range(4)]) for k in keys]
print(f"REBUILD {len(keys)} cases: drift per slot geomean {gm([e[0] for e in eff]):.3f}; after/before effect geomean {gm([e[1] for e in eff]):.3f}, median {st.median([e[1] for e in eff]):.3f}")
for mode in ('DEFERRED','EAGER'):
    ee=[fit([R[j][k] for j in range(4)])[1] for k in keys if f' {mode} ' in k]
    print(f"  {mode}: effect geomean {gm(ee):.3f}  cases over 1.05: {sum(x>1.05 for x in ee)}  over 1.10: {sum(x>1.10 for x in ee)}  max {max(ee):.3f}")
ba=[(gm([R[1][k],R[3][k]])/gm([R[0][k],R[2][k]])) for k in keys]
print(f"  plain mean(after)/mean(before): geomean {gm(ba):.3f}; run2/run1 {gm([R[1][k]/R[0][k] for k in keys]):.3f}, run3/run1 {gm([R[2][k]/R[0][k] for k in keys]):.3f}, run4/run1 {gm([R[3][k]/R[0][k] for k in keys]):.3f}, run4/run3 {gm([R[3][k]/R[2][k]  for k in keys]):.3f}")
big=sorted(zip(ba,keys),reverse=True)[:5]
print("  largest plain after/before ratios:"); [print(f"    {r:.3f} {k}") for r,k in big]
print("PROJECTION warm (us/op or B/op): runs 1..4, then fitted effect")
for k in sorted(P[0]):
    if k[0].startswith('warm') and k[1] in ('saturate','saturate:gc.alloc.rate.norm','extractAll','extractAll:gc.alloc.rate.norm') and all(k in p for p in P):
        v=[p[k] for p in P]; e=fit(v)[1]
        print(f"  {k[0]:13s} {k[1]:30s} n={k[2]:5s} "+' '.join(f'{x:12.1f}' for x in v)+f"   effect x{e:.3f}")
