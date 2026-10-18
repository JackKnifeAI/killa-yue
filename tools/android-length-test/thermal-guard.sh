#!/system/bin/sh
# ADB-shell benchmark helper; never installed as an application service.
# Owns one CPU-only child. Thermal limits below are conservative test policy,
# not Samsung's specified limits. OS thermal management stays enabled.
set -u
run_dir=$1
context=$2
base=/data/local/tmp/killa-s20-test
mkdir -p "$run_dir"
child=''
cleanup() {
    if [ -n "$child" ] && kill -0 "$child" 2>/dev/null; then
        kill -CONT "$child" 2>/dev/null
        kill -TERM "$child" 2>/dev/null
    fi
}
trap cleanup EXIT HUP INT TERM
cd "$base" || exit 1
env LD_LIBRARY_PATH="$base" VK_DRIVER_FILES="$base/turnip.json" \
    VK_ICD_FILENAMES="$base/turnip.json" YUE_THREADS=2 \
    YUE_AR_PAUSE_MS=15 YUE_NAR_PAUSE_MS=1500 \
    MESA_SHADER_CACHE_DIR="$base/mesa-cache" \
    /system/bin/nice -n 10 "$base/yue-server-fixed" \
    --model /sdcard/KillaYuE/models/YuE2-3B-Q5_K_M.gguf \
    --vae /sdcard/KillaYuE/models/YuE2-Vae-F32.gguf \
    --device CPU --host 127.0.0.1 --port 18087 \
    --max-seq "$context" --max-batch 1 > "$run_dir/engine.log" 2>&1 &
child=$!
echo "$child" > "$run_dir/engine.pid"
kill -STOP "$child"
paused=1
cool=0
echo 'epoch,status,battery_c,skin_c,ap_c,battery_pct,available_kib,rss_kib,hwm_kib,paused' > "$run_dir/thermal.csv"
while kill -0 "$child" 2>/dev/null; do
    if [ -e "$run_dir/STOP" ]; then break; fi
    thermal=$(timeout 8 dumpsys thermalservice 2>/dev/null)
    battery=$(timeout 8 dumpsys battery 2>/dev/null)
    status=$(printf '%s\n' "$thermal" | sed -n 's/^Thermal Status: //p' | head -1)
    skin=$(printf '%s\n' "$thermal" | sed -n 's/.*mValue=\([0-9.]*\).*mName=SKIN,.*/\1/p' | head -1)
    ap=$(printf '%s\n' "$thermal" | sed -n 's/.*mValue=\([0-9.]*\).*mName=AP,.*/\1/p' | head -1)
    bat10=$(printf '%s\n' "$battery" | sed -n 's/^  temperature: //p' | head -1)
    level=$(printf '%s\n' "$battery" | sed -n 's/^  level: //p' | head -1)
    bat=$(awk -v v="$bat10" 'BEGIN {if(v!="") printf "%.1f",v/10}')
    # Missing sensors fail closed. Three cool readings give hysteresis.
    decision=$(awk -v s="$status" -v b="$bat" -v k="$skin" -v a="$ap" -v l="$level" 'BEGIN {
        if(s==""||b==""||k==""||a==""||l=="") {print "pause";exit}
        if(s>=4||b>=45||k>=45||a>=85||l<=15) {print "stop";exit}
        if(s>=3||b>=42||k>=42||a>=75||l<=20) {print "pause";exit}
        if(s<=1&&b<=39&&k<=39&&a<=65&&l>20) print "cool"; else print "hold"
    }')
    now=$(date +%s)
    if [ "$decision" = stop ]; then
        echo "$now safety-stop status=$status battery=$bat skin=$skin ap=$ap level=$level" >> "$run_dir/events.log"
        echo safety-stop > "$run_dir/terminal"
        break
    elif [ "$decision" = pause ]; then
        cool=0
        if [ "$paused" = 0 ]; then
            kill -STOP "$child" || break
            paused=1
            echo "$now paused-to-cool status=$status battery=$bat skin=$skin ap=$ap" >> "$run_dir/events.log"
        fi
    elif [ "$decision" = cool ]; then
        cool=$((cool + 1))
        if [ "$paused" = 1 ] && [ "$cool" -ge 3 ]; then
            kill -CONT "$child" || break
            paused=0
            echo "$now resumed status=$status battery=$bat skin=$skin ap=$ap" >> "$run_dir/events.log"
        fi
    else
        cool=0
    fi
    avail=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
    rss=$(awk '/^VmRSS:/ {print $2}' "/proc/$child/status" 2>/dev/null)
    hwm=$(awk '/^VmHWM:/ {print $2}' "/proc/$child/status" 2>/dev/null)
    echo "$now,$status,$bat,$skin,$ap,$level,$avail,$rss,$hwm,$paused" >> "$run_dir/thermal.csv"
    sleep 15
done
cleanup
wait "$child"
rc=$?
echo "$rc" > "$run_dir/exit-code"
[ -f "$run_dir/terminal" ] || echo engine-exited > "$run_dir/terminal"
child=''
