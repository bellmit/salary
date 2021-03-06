package cn.com.pushworld.salary.bs.dinterface;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import cn.com.infostrategy.bs.common.CommDMO;
import cn.com.infostrategy.bs.common.WLTJobIFC;
import cn.com.infostrategy.to.common.HashVO;
import cn.com.infostrategy.to.common.TBUtil;
import cn.com.infostrategy.to.common.WLTLogger;
import cn.com.infostrategy.to.mdata.InsertSQLBuilder;
import cn.com.pushworld.salary.bs.SalaryFormulaDMO;

/**
 * 平台定时任务调用此类
 * 每次执行的总任务日志。
 * @author haoming
 * create by 2014-7-10
 */
public class SynDataRunTimer implements WLTJobIFC {
	private CommDMO dmo = new CommDMO();
	private DataInterfaceDMO ifcdmo = new DataInterfaceDMO();
	Logger logger = WLTLogger.getLogger(SynDataRunTimer.class); //

	public String run() throws Exception {
		//
		String files[] = ifcdmo.getFiledesc();
		boolean flag = true;
		if (files == null || files.length == 0) {
			if (TBUtil.isEmpty(ifcdmo.ftppath)) {
				throw new Exception("目前服务器没有配置FTP路径，请配置系统参数[数据接口FTP路径]");
			} else {
				throw new Exception("目前服务器路径" + ifcdmo.ftppath + "没有找到任何上传记录");
			}
		}
		HashVO hisjob[] = dmo.getHashVoArrayByDS(null, "select * from SAL_SYN_DATA_JOB order by datadate desc "); //历史所有job
		List loglist = new ArrayList();
		//比较任务历史和目录
		if (hisjob != null && hisjob.length > 0) {
			HashVO nearJob = hisjob[0];//最近的任务
			String nearJobDate = nearJob.getStringValue("datadate"); //2014-07-04
			List needdo = new ArrayList();
			for (int i = 0; i < files.length; i++) {
				if (!files[i].equals(nearJobDate.replace("-", ""))) {
					needdo.add(files[i]);
				} else {
					break; //如果找到同一天，立马退出
				}
			}
			for (int i = 0; i < needdo.size(); i++) { //先执行离最后一天最近的一次。
				String filefolder = (String) needdo.get(needdo.size() - i - 1);
				boolean rtfalg = onedataJob(filefolder, loglist); //执行一次任务。
				if (flag && !rtfalg) {
					flag = false;
				}
			}
		} else if (hisjob.length == 0) {
			for (int i = 0; i < files.length; i++) {
				boolean rtfalg = onedataJob(files[files.length - i - 1], loglist);
				if (flag && !rtfalg) {
					flag = false;
				}
			}
		}
		dmo.executeBatchByDS(null, loglist);
		System.gc(); //建议虚拟机释放内存.....
		if (!flag) {
			logger.error("数据接口同步定时任务执行失败");
			return "失败";
		}
		logger.info("数据接口同步定时任务执行成功");
		return "成功";
	}

	/**
	 * 执行一次任务。
	 * @param _currFolder 日期目录名称
	 * @param joblog 
	 * @return
	 * @throws Exception
	 */
	private boolean onedataJob(String _currFolder, List joblog) throws Exception {
		String jobid = dmo.getSequenceNextValByDS(null, "S_SAL_SYN_DATA_JOB");
		String time1 = TBUtil.getTBUtil().getCurrTime();
		long t1 = System.currentTimeMillis();
		StringBuffer descr = new StringBuffer();
		boolean flag_syn = false;
		//传入为目录
		String datadate = _currFolder.substring(0, 4) + "-" + _currFolder.substring(4, 6) + "-" + _currFolder.substring(6, 8);
		String ex1 = "";
		try {
			flag_syn = ifcdmo.syn_data_bytimer(jobid, datadate); //任务第一步，执行接口数据同步
		} catch (Exception ex) {
			flag_syn = false;
			ex1 = ex.toString();
		}
		String time2 = TBUtil.getTBUtil().getCurrTime();
		long t2 = System.currentTimeMillis();
		descr.append("与数据平台同步数据始于:" + time1 + ",终于:" + time2 + ",耗时:" + getDifferTime(t2 - t1) + ",同步结果【" + (flag_syn ? "成功" : "失败") + "】");
		if (!flag_syn && ex1.length() > 0) {
			descr.append("\r\n原因:" + ex1);
			if (descr.length() > 1300) {
				descr = new StringBuffer(descr.substring(0, 1300)).append("...");
			}
		}
		boolean flag_convert = false;
		int flag_auto_calc = 1; //自动计算指标。
		String time4 = TBUtil.getTBUtil().getCurrTime();
		String ex2 = "";
		try {
			flag_convert = ifcdmo.convertIFCDataToReport(jobid, datadate);//任务第二步，执行接口数据报表转换,并执行数据应用
		} catch (Exception ex) {
			flag_convert = false;
			ex2 = ex.toString();
		}
		String time3 = TBUtil.getTBUtil().getCurrTime();
		long t3 = System.currentTimeMillis();
		descr.append("\r\n同步数据分析并转换始于:" + time2 + ",终于:" + time3 + ",耗时:" + getDifferTime(t3 - t2) + ",同步结果【" + (flag_convert ? "成功" : "失败") + "】");
		if (!flag_convert && ex2.length() > 0) {
			descr.append("\r\n原因:" + ex2);
			if (descr.length() > 2600) {
				descr = new StringBuffer(descr.substring(0, 2600)).append("...");
			}
		}
		String ex3 = "";
		time4 = TBUtil.getTBUtil().getCurrTime();
		if (flag_syn && flag_convert) {//
			try {
				flag_auto_calc = new SalaryFormulaDMO().autoCalcPersonDLTargetByTimer(jobid, datadate); //任务第三步，计算员工定量指标
			} catch (Exception ex) {
				flag_auto_calc = -1;
				ex3 = ex.toString();
			}
		}
		long t4 = System.currentTimeMillis();
		if (flag_auto_calc == 0) {
			descr.append("\r\n系统中未配置自动计算绩效的定量指标.");
		} else {
			descr.append("\r\n定量指标自动计算开始于:" + time3 + ",终于:" + time4 + ",耗时:" + getDifferTime(t4 - t3) + ",同步结果【" + (flag_auto_calc == 1 ? "成功" : "失败") + "】");
		}
		if (flag_auto_calc == -1) {
			descr.append("\r\n原因:" + ex3);
		}

		if (descr.length() > 3900) {
			descr = new StringBuffer(descr.substring(0, 3900));
		}
		InsertSQLBuilder insert = new InsertSQLBuilder("SAL_SYN_DATA_JOB");
		insert.putFieldValue("id", jobid);
		insert.putFieldValue("starttime", time1);
		insert.putFieldValue("endtime", time4);
		insert.putFieldValue("datadate", datadate);
		insert.putFieldValue("state", (!flag_syn || !flag_convert || flag_auto_calc == -1) ? "失败" : "成功");
		insert.putFieldValue("descr", descr.toString());
		joblog.add(insert);
		logger.info(descr.toString());
		if (!flag_syn || !flag_convert || flag_auto_calc == -1) {
			return false;
		}
		return true;
	}

	/*
	 * 根据一个毫秒时差，转换为时分秒差
	 */
	public String getDifferTime(long mss) {
		long days = mss / (1000 * 60 * 60 * 24);
		long hours = (mss % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
		long minutes = (mss % (1000 * 60 * 60)) / (1000 * 60);
		long seconds = (mss % (1000 * 60)) / 1000;
		StringBuffer sb = new StringBuffer();
		if (days > 0) {
			sb.append(days + "天");
		}
		if (hours > 0 || sb.length() > 0) {
			sb.append(hours + "小时");
		}
		if (minutes > 0 || sb.length() > 0) {
			sb.append(minutes + "分");
		}
		if (seconds > 0 || sb.length() > 0) {
			sb.append(seconds + "秒");
		}
		if (sb.length() == 0) {
			sb.append(mss + "毫秒");
		}
		return sb.toString();
	}
}
