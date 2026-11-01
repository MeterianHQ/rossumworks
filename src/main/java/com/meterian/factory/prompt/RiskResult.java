package com.meterian.factory.prompt;

import com.meterian.factory.state.RiskLevel;
import java.util.List;

public record RiskResult(RiskLevel risk, boolean small, List<String> reasons) {
}
