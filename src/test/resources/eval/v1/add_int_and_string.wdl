version development

workflow add_int_and_string {
  input {
    Int subset_n
    Int subset_total
  }

  String subset_param1 = if subset_total > 1 then "-q ${subset_n}/${subset_total}" else ""
  String subset_param2 = if subset_total > 1 then '${"-q " + subset_n + "/" + subset_total}' else ""
  #this one doesn't work:
  #String subset_param3 = if subset_total > 1 then "-q " + subset_n + "/" + subset_total else ""
}