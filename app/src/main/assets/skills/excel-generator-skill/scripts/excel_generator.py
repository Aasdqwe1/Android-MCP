#!/usr/bin/env python3
# skill:excel-generator / script:generate — 从JSON数据生成专业Excel表格
import sys, json, re
from collections import Counter
from datetime import datetime

# 列类型众数并列时的固定优先级（越靠前越优先），保证结果可复现
_TYPE_PRIORITY = ["number", "currency", "percent", "datetime", "date", "text"]

from openpyxl import Workbook
from openpyxl.styles import Font, Alignment, PatternFill, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.views import SheetView
from openpyxl.formatting.rule import Rule
from openpyxl.styles.differential import DifferentialStyle
from openpyxl.styles import Color
from openpyxl.chart import BarChart, LineChart, Reference, Series
from openpyxl.chart.label import DataLabelList
# 兼容旧版 openpyxl
try:
    from openpyxl.worksheet.freeze import freeze_panes
except ImportError:
    def freeze_panes(ws, cell):
        ws.freeze_panes = cell

def detect_type(value):
    """智能检测数据类型"""
    if value is None or value == "":
        return "empty"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        # 检查是否为日期时间
        date_patterns = [
            r'^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?)?$',
            r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$',
        ]
        for pattern in date_patterns:
            if re.match(pattern, value):
                return "datetime"
        # 检查是否为百分比
        if re.match(r'^-?\d+(\.\d+)?%$', value):
            return "percent"
        # 检查是否为金额（含¥/$/€或逗号分隔）
        if re.match(r'^[¥$€]?\d{1,3}(,\d{3})*(\.\d{1,2})?$', value):
            return "currency"
        # 检查是否为纯数字字符串
        if re.match(r'^-?\d+(\.\d+)?$', value):
            return "number"
        return "text"
    return "text"

def format_value(value, data_type):
    """根据类型格式化显示值"""
    if value is None or value == "":
        return ""
    if data_type == "currency":
        if isinstance(value, (int, float)):
            return f"{value:,.2f}"
        return value
    if data_type == "number":
        if isinstance(value, (int, float)):
            if isinstance(value, float) and value == int(value):
                return int(value)
            return value
        return value
    if data_type == "percent":
        val = str(value).replace('%', '')
        try:
            num = float(val)
            return num / 100
        except:
            return value
    if data_type == "datetime":
        try:
            return str(value)
        except:
            return value
    return value

def generate_excel(data, output_path, title=""):
    if not data:
        print("错误: 数据为空")
        return False

    wb = Workbook()
    ws = wb.active
    if title:
        ws.title = title[:31]

    headers = list(data[0].keys())

    # 定义样式
    header_font = Font(name='微软雅黑', size=11, bold=True, color="FFFFFF")
    header_fill = PatternFill(start_color="1A3C5E", end_color="1A3C5E", fill_type="solid")
    header_alignment = Alignment(horizontal="center", vertical="center")
    header_border = Border(
        bottom=Side(style='medium', color="0D233A"),
        top=Side(style='medium', color="0D233A"),
        left=Side(style='thin', color="8BA3C7"),
        right=Side(style='thin', color="8BA3C7")
    )

    # 交替行颜色
    even_fill = PatternFill(start_color="F5F7FA", end_color="F5F7FA", fill_type="solid")
    odd_fill = PatternFill(start_color="FFFFFF", end_color="FFFFFF", fill_type="solid")
    body_border = Border(
        left=Side(style='thin', color="D0D7E5"),
        right=Side(style='thin', color="D0D7E5"),
        top=Side(style='thin', color="D0D7E5"),
        bottom=Side(style='thin', color="D0D7E5")
    )

    # 写入表头
    for col, header in enumerate(headers, 1):
        cell = ws.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill
        cell.alignment = header_alignment
        cell.border = header_border

    # 检测每列数据类型
    col_types = {}
    for col_idx, header in enumerate(headers, 1):
        types = []
        for row_idx, record in enumerate(data, 2):
            val = record.get(header, "")
            t = detect_type(val)
            if t != "empty":
                types.append(t)
        # 取最常见的类型；并列时按固定优先级，避免 set 迭代顺序（字符串哈希随机化）
        # 让同一份数据在不同进程里判出不同的列类型
        if types:
            counts = Counter(types)
            col_types[header] = min(
                counts.items(),
                key=lambda kv: (-kv[1], _TYPE_PRIORITY.index(kv[0]) if kv[0] in _TYPE_PRIORITY else 99),
            )[0]
        else:
            col_types[header] = "text"

    # 写入数据
    for row_idx, record in enumerate(data, 2):
        # 交替行背景
        row_fill = even_fill if row_idx % 2 == 0 else odd_fill

        for col_idx, header in enumerate(headers, 1):
            raw_val = record.get(header, "")
            data_type = col_types.get(header, "text")
            cell_val = format_value(raw_val, data_type)
            cell = ws.cell(row=row_idx, column=col_idx, value=cell_val)

            cell.border = body_border
            cell.fill = row_fill

            # 对齐
            if data_type in ("number", "currency", "percent"):
                cell.alignment = Alignment(horizontal="right", vertical="center")
            elif data_type == "datetime":
                cell.alignment = Alignment(horizontal="center", vertical="center")
            else:
                cell.alignment = Alignment(horizontal="left", vertical="center")

    # 智能列宽
    for col_idx, header in enumerate(headers, 1):
        max_len = len(header) + 2
        for row in range(1, min(len(data) + 2, 201)):
            val = ws.cell(row=row, column=col_idx).value
            if val is not None:
                max_len = max(max_len, len(str(val)) + 2)
        # 限制列宽 10~50
        ws.column_dimensions[get_column_letter(col_idx)].width = min(max(10, max_len), 50)

    # 冻结首行
    freeze_panes(ws, 'A2')

    # 自适应行高
    for row in range(1, len(data) + 2):
        ws.row_dimensions[row].height = 18

    # ---- 条件格式：数据条 ----
    # 找数值列（数字类型）加数据条
    for col_idx, header in enumerate(headers, 1):
        data_type = col_types.get(header, "text")
        if data_type in ("number", "currency"):
            col_letter = get_column_letter(col_idx)
            start_row = 2
            end_row = len(data) + 1
            range_ref = f"{col_letter}{start_row}:{col_letter}{end_row}"

            # 数据条（蓝色渐变）
            dxf = DifferentialStyle()
            # 使用 DataBar 规则需要额外的扩展库，简化用颜色渐变
            # 直接用条件格式：根据值大小设颜色（绿色越高）
            from openpyxl.formatting.rule import ColorScaleRule
            from openpyxl.styles import Color

            # 获取该列所有数值
            values = []
            for row_idx, record in enumerate(data, 2):
                val = record.get(header)
                if isinstance(val, (int, float)):
                    values.append(val)
            if values:
                min_val = min(values)
                max_val = max(values)
                if max_val > min_val:
                    # 三色渐变
                    rule = ColorScaleRule(
                        start_type='num', start_value=min_val, start_color='F8696B',
                        mid_type='num', mid_value=(min_val+max_val)/2, mid_color='FFEB84',
                        end_type='num', end_value=max_val, end_color='63BE7B'
                    )
                    ws.conditional_formatting.add(range_ref, rule)

    # ---- 图标集：同比变化（用箭头） ----
    # 找含有"同比"或"变化"的列，加箭头
    for col_idx, header in enumerate(headers, 1):
        if "同比" in header or "变化" in header:
            col_letter = get_column_letter(col_idx)
            start_row = 2
            end_row = len(data) + 1
            range_ref = f"{col_letter}{start_row}:{col_letter}{end_row}"
            # 用 IconSetRule （需要先检测数值）
            # 简化：使用文本方向指示
            from openpyxl.formatting.rule import IconSetRule, IconSet
            # 检查是否都是百分比值
            try:
                rule = IconSetRule(
                    icon_style='3Arrows',
                    type='percent',
                    values=[0, 33, 67]
                )
                ws.conditional_formatting.add(range_ref, rule)
            except:
                pass

    # ---- 自动创建图表 ----
    # 找数值列和文本列，自动生成柱状图
    num_cols = []
    text_cols = []
    for col_idx, header in enumerate(headers, 1):
        data_type = col_types.get(header, "text")
        if data_type in ("number", "currency"):
            num_cols.append((col_idx, header))
        elif data_type == "text" and len(data) > 1:
            # 取第一列文本作为分类轴
            if col_idx == 1 or "城市" in header or "区域" in header:
                text_cols.append((col_idx, header))

    # 如果有数值列，创建图表
    if num_cols and len(data) >= 3:
        # 图表放在数据下方（空两行）
        chart_row = len(data) + 4

        # 使用第一个文本列作为分类轴
        cat_col_idx = text_cols[0][0] if text_cols else 1
        cat_col_letter = get_column_letter(cat_col_idx)
        cat_data = Reference(ws, min_col=cat_col_idx, min_row=2, max_row=len(data)+1)

        for chart_idx, (num_col_idx, num_header) in enumerate(num_cols[:3]):  # 最多3个数值列
            col_letter = get_column_letter(num_col_idx)

            # 创建柱状图
            chart = BarChart()
            chart.title = f"{num_header} 对比"
            chart.x_axis.title = text_cols[0][1] if text_cols else "项目"
            chart.y_axis.title = num_header

            # 数据
            data_ref = Reference(ws, min_col=num_col_idx, min_row=2, max_row=len(data)+1)
            chart.add_data(data_ref, titles_from_data=False)

            # 设置分类轴
            chart.set_categories(cat_data)

            # 显示数值标签
            chart.dataLabels = DataLabelList()
            chart.dataLabels.showVal = True

            # 样式
            chart.style = 10
            chart.width = 12
            chart.height = 8

            # 放置在数据下方，横向排列
            chart_col = 1 + chart_idx * 13
            ws.add_chart(chart, f"{get_column_letter(chart_col)}{chart_row}")

            # 如果有多个数值列，再加一个折线图对比趋势
            if chart_idx == len(num_cols) - 1 and len(num_cols) >= 2:
                line_chart = LineChart()
                line_chart.title = "趋势对比"
                line_chart.x_axis.title = text_cols[0][1] if text_cols else "项目"
                line_chart.y_axis.title = "数值"

                for nc_idx, (nc, nh) in enumerate(num_cols):
                    ref = Reference(ws, min_col=nc, min_row=2, max_row=len(data)+1)
                    series = Series(ref, title=nh)
                    line_chart.series.append(series)

                line_chart.set_categories(cat_data)
                line_chart.width = 14
                line_chart.height = 8
                line_chart.style = 12

                chart_col_line = 1 + (len(num_cols)) * 13
                ws.add_chart(line_chart, f"{get_column_letter(chart_col_line)}{chart_row}")

    wb.save(output_path)
    print(f"✅ Excel 已生成: {output_path}")
    print(f"📊 共 {len(data)} 行记录, {len(headers)} 列")
    print(f"📁 文件大小: {__import__('os').path.getsize(output_path) / 1024:.1f} KB")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(json.dumps({"error": "缺少必填参数：需要 data_json, output_path（用法 python3 excel_generator.py <data_json> <output_path> [title]）"}, ensure_ascii=False))
        sys.exit(1)
    data = json.loads(sys.argv[1])
    output_path = sys.argv[2]
    title = sys.argv[3] if len(sys.argv) > 3 else ""
    generate_excel(data, output_path, title)