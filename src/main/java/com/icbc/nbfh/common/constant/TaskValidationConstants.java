package com.icbc.nbfh.common.constant;

import lombok.RequiredArgsConstructor;

import java.util.regex.Pattern;

@RequiredArgsConstructor
public class TaskValidationConstants {

    public static final Pattern TASK_NO_PATTERN = Pattern.compile("TASK\\d{12}");

    public static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
}
