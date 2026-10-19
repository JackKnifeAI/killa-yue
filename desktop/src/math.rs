use serde_json::{json, Value};

pub const INNER: usize = 256;
pub const ROWS: usize = 16;
pub const COLUMNS: usize = 8;
pub const INPUTS: usize = INNER * (ROWS + COLUMNS);
pub const OUTPUTS: usize = ROWS * COLUMNS;

pub fn inputs() -> Vec<f32> {
    let mut data = vec![0.0; INPUTS + OUTPUTS];
    for (i, v) in data[..INNER * ROWS].iter_mut().enumerate() {
        *v = (((i * 17 + i / INNER * 3) % 31) as i32 - 15) as f32 / 16.0;
    }
    for c in 0..COLUMNS {
        for k in 0..INNER {
            data[INNER * ROWS + c * INNER + k] =
                if k % 3 == 0 { -1.0 } else { 1.0 } * (c + 1) as f32 / 8.0;
        }
    }
    // Detect an unexecuted kernel or an output element that was never written.
    data[INPUTS..].fill(f32::NAN);
    data
}

pub fn validate(data: &[f32]) -> Value {
    if data.len() != INPUTS + OUTPUTS {
        return json!({"status": "fail", "reason": "wrong result size"});
    }
    let original = inputs();
    let mut pass = data[..INPUTS] == original[..INPUTS];
    let mut max_error = 0.0_f64;
    let mut finite = true;
    for c in 0..COLUMNS {
        for r in 0..ROWS {
            let expected = (0..INNER)
                .map(|k| {
                    f64::from(original[r * INNER + k])
                        * f64::from(original[INNER * ROWS + c * INNER + k])
                })
                .sum::<f64>();
            let actual = f64::from(data[INPUTS + c * ROWS + r]);
            let error = (actual - expected).abs();
            finite &= actual.is_finite();
            if error.is_finite() {
                max_error = max_error.max(error);
            }
            pass &= actual.is_finite() && error <= 0.03 + 0.002 * expected.abs();
        }
    }
    json!({"status": if pass { "pass" } else { "fail" }, "test": "f32_16x256x8", "finite": finite, "max_absolute_error": max_error, "input_preserved": data[..INPUTS] == original[..INPUTS]})
}

pub fn cpu() -> Value {
    let mut data = inputs();
    for c in 0..COLUMNS {
        for r in 0..ROWS {
            let mut sum = 0.0_f32;
            for k in 0..INNER {
                sum += data[r * INNER + k] * data[INNER * ROWS + c * INNER + k];
            }
            data[INPUTS + c * ROWS + r] = sum;
        }
    }
    validate(&data)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_missing_kernel_nan_and_corrupt_output() {
        assert_eq!(validate(&inputs())["status"], "fail");
        assert_eq!(cpu()["status"], "pass");
        let mut data = inputs();
        data[INPUTS..].fill(0.0);
        assert_eq!(validate(&data)["status"], "fail");
        assert_eq!(validate(&[])["status"], "fail");
    }
}
